package app.futbolio.engine

import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.api.Failures
import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.CatalogVisibility
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.api.Configurations
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.engine.adapters.providers.shared.StaticPlayerData
import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.engine.runtime.TransportOwnership
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*

/** Explicit live diagnostic: inspect the same fixtures across independently discovered providers. */
class MatchStreamDiagnosticsTest {
    private val target = Regex("(?i)psv|brugge|leipzig|manchester city|man city|rbl")
    @Test fun traceTarget720PlayerStages() = runBlocking<Unit> {
        HttpUpstream().use { net ->
            val providers = ProviderRegistry()
            val source = Configurations.sources().first { it.kind == "720pstream" }
            val rows = providers.get(source.kind).discover(source, net).listings.filter { it.title.startsWith("PSV Eindhoven vs") }
            val traced = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?) = net.open(url, headers, range)
                override suspend fun text(url: String, headers: SourceHeaders): Document {
                    val doc = net.text(url, headers)
                    val json = runCatching { codec.parseToJsonElement(doc.text).obj() }.getOrNull()
                    println("720 STAGE host=${Web.url(doc.url).host}; bytes=${doc.text.length}; fields=${json?.keys}; success=${json?.b("success")}")
                    if (json == null) println("720 STATIC DATA objectKeys=${StaticPlayerData.objects(doc.text).filter { it.size > 3 }.map { it.keys }}")
                    System.getenv("FUTBOLIO_TRACE_DIRECTORY")?.let { directory ->
                        val folder = java.nio.file.Path.of(directory)
                        java.nio.file.Files.createDirectories(folder)
                        val file = java.nio.file.Files.createTempFile(folder, "720-stage-", ".html")
                        java.nio.file.Files.writeString(file, doc.text)
                        println("720 private diagnostic snapshot: $file (may contain session credentials; do not publish)")
                    }
                    return doc
                }
            }
            for (row in rows) {
                try {
                    val choices = withTimeout(25_000) { providers.get(source.kind).resolve(row, traced) }
                    println("720 TRACE ${row.title}: choices=${choices.size}")
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    val safe = failure.message?.takeIf { it in setOf("720pstream session unavailable", "Missing or ambiguous embed configuration",
                        "Ambiguous published player configurations", "Missing player field", "Ambiguous player field", "720pstream player data layout changed") }
                    println("720 TRACE FAILURE ${row.title}: ${safe ?: Failures.describe(failure)}; at=${failure.stackTrace.take(4).map { it.methodName to it.lineNumber }}")
                }
            }
        }
    }
    @Test fun compareCurrentMatchSources() = runBlocking<Unit> {
        HttpUpstream().use { net ->
            val providers = ProviderRegistry()
            val rows = Configurations.sources().filter { it.kind != "ppv" }.map { source -> async {
                try {
                    val result = withTimeout(35_000) { DomainRecovery(net, providers).discover(source) }
                    println("${source.name}: ${result.listings.size} listings; matches=${result.listings.count { !it.channel }}")
                    result.listings.filter { !it.channel && target.containsMatchIn(it.title) }.forEach {
                        println("LISTING ${source.name}: ${it.title}; league=${it.league}; start=${MatchNormalizer.kickoff(it, System.currentTimeMillis())}; players=${it.players.size}; id=${it.id}")
                    }
                    result.listings
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    println("${source.name}: ${Failures.describe(failure)}")
                    emptyList()
                }
            } }.awaitAll().flatten()
            val now = System.currentTimeMillis()
            val selected = rows.filter { target.containsMatchIn(it.title) && kotlin.math.abs(MatchNormalizer.kickoff(it, now) - now) < 24 * CatalogVisibility.HOUR }
            MatchNormalizer.aggregate(selected, emptyList(), PresentationConfig(), System.currentTimeMillis()).forEach {
                println("GROUP ${it.title}: ${it.sources.joinToString()}")
            }
            selected.map { item -> async {
                try {
                    val choices = withTimeout(25_000) { providers.get(item.source.kind).resolve(item, net) }
                    println("RESOLVED ${item.source.name}: ${item.title}; choices=${choices.size}; players=${item.players.map { it.target }}")
                    for (choice in choices.take(2)) {
                        try {
                            val segments = withTimeout(15_000) { SegmentProbe(net).probe(choice, true) }
                            println("MEDIA ${item.source.name}: ${item.title}; verifiedBytes=${segments.sumOf { it.bytes }}; formats=${segments.map { it.format }.distinct()}")
                            break
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            println("MEDIA FAILED ${item.source.name}: ${item.title}; ${Failures.describe(failure)}")
                        }
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    println("RESOLVE FAILED ${item.source.name}: ${item.title}; ${failure.javaClass.simpleName}; ${Failures.describe(failure)}")
                }
            } }.awaitAll()
        }
    }

    @Test fun currentMatchesExposeMergedProvidersThroughTheRealStremioApi() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        HttpUpstream().use { net ->
            val engine = FutbolioEngine(HostConfig(port), Configurations.sources(), PosterRenderer { _, _ -> byteArrayOf() }, net,
                ownership = TransportOwnership.BORROWED)
            val local = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = withContext(Dispatchers.IO) {
                    val uri = URI(url)
                    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port)
                    val connection = uri.toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000; connection.readTimeout = 35_000
                    if (range != null) connection.setRequestProperty("Range", range)
                    val status = connection.responseCode
                    val body = if (status in 200..299) connection.inputStream else connection.errorStream ?: byteArrayOf().inputStream()
                    UpstreamResponse(status, url, mapOf("content-type" to connection.contentType.orEmpty()), body) {
                        body.close(); connection.disconnect()
                    }
                }
            }
            try {
                withTimeout(120_000) {
                    engine.start(); engine.catalog.refresh().await()
                    val metas = codec.parseToJsonElement(local.text("${engine.host.base}/catalog/tv/live.json").text).obj().a("metas")
                        .map { it.obj() }.filter { target.containsMatchIn(it.s("name")) }
                    assumeTrue("Target matches are no longer live in the current catalog", metas.isNotEmpty())
                    for (meta in metas) {
                        val streams = codec.parseToJsonElement(local.text("${engine.host.base}/stream/tv/${Web.enc(meta.s("id"))}.json").text).obj().a("streams").map { it.obj() }
                        println("STREMIO ${meta.s("name")}: ${streams.size} streams; ${streams.map { it.s("name") }}")
                        engine.diagnosticSnapshot().events.filter { it.contains("Resolution") }.forEach { println("STREMIO DIAGNOSTIC $it") }
                        assertTrue(streams.isNotEmpty(), "No streams for ${meta.s("name")}")
                        assertTrue(streams.none { "externalUrl" in it }, "Website-only choices must not appear in Stremio")
                        // Verify at least one real local relay stream for each represented provider.
                        for ((provider, choices) in streams.groupBy { it.s("name").substringBefore(" · ") }) {
                            var verified = false
                            for (choice in choices.take(2)) {
                                try {
                                    val segments = withTimeout(15_000) { SegmentProbe(local).probe(StreamChoice("Live relay", choice.s("url"), mediaType = "hls"), true) }
                                    println("STREMIO MEDIA ${meta.s("name")}: $provider; verifiedBytes=${segments.sumOf { it.bytes }}; nativePlaybackVerified=false")
                                    verified = true
                                    break
                                } catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    println("STREMIO MEDIA FAILED ${meta.s("name")}: $provider; ${Failures.describe(failure)}")
                                }
                            }
                            println("STREMIO PROVIDER ${meta.s("name")}: $provider; mediaVerified=$verified")
                        }
                    }
                }
            } finally { engine.shutdown() }
        }
    }
}
