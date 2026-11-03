package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.api.Configurations
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.engine.runtime.TransportOwnership
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*

/** Live Stremio HTTP path, including rewriting, credential forwarding and AES decryption.
 * This is transport verification, NOT native Stremio video/audio acceptance. */
@RunWith(Parameterized::class)
class LiveStremioRelayVerificationTest(private val label: String, private val source: SourceConfig) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun providers() = Configurations.sources().filter { it.kind in setOf("papaz", "taraftarium", "futbolvivo", "720pstream") }
            .map { arrayOf<Any>(it.name, it) }
    }

    @Test fun stremioStreamApiRelaysRecognizedLiveMedia() = runBlocking {
        val directory = File("build/reports/live-relay").apply { mkdirs() }
        val report = File(directory, "${source.id}.json")
        fun write(outcome: String, scope: String = "", bytes: Long = 0, formats: List<String> = emptyList()) {
            report.writeText(buildJsonObject {
                put("provider", label); put("outcome", outcome); put("scope", scope); put("mediaBytes", bytes)
                put("formats", JsonArray(formats.map(::JsonPrimitive))); put("nativePlaybackVerified", false)
                put("checkedAt", System.currentTimeMillis())
            }.toString())
        }
        write("RUNNING")
        val port = ServerSocket(0).use { it.localPort }
        HttpUpstream().use { net ->
            val engine = FutbolioEngine(HostConfig(port), listOf(source), PosterRenderer { _, _ -> byteArrayOf() }, net,
                ownership = TransportOwnership.BORROWED)
            val local = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = withContext(Dispatchers.IO) {
                    val uri = URI(url)
                    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port)
                    val connection = uri.toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000; connection.readTimeout = 15_000
                    if (range != null) connection.setRequestProperty("Range", range)
                    val status = connection.responseCode
                    val body = if (status in 200..299) connection.inputStream else connection.errorStream ?: byteArrayOf().inputStream()
                    UpstreamResponse(status, url, mapOf("content-type" to connection.contentType.orEmpty()), body) {
                        body.close(); connection.disconnect()
                    }
                }
            }
            try {
                withTimeout(90_000) {
                    engine.start(); engine.catalog.refresh().await()
                    val now = System.currentTimeMillis()
                    val rows = engine.catalog.items()
                    val matches = rows.filterNot { it.channel }.filter {
                        val kickoff = MatchNormalizer.kickoff(it, now)
                        kickoff > 0 && kickoff <= now && kickoff + 3 * 3_600_000 > now
                    }
                    val candidates = matches.take(2) + rows.filter { it.channel }.take(2)
                    if (candidates.isEmpty()) write("NO_ACTIVE_MEDIA")
                    assumeTrue("No started match or continuous channel currently published", candidates.isNotEmpty())
                    for (row in candidates) {
                        try {
                            val verified = withTimeout(20_000) {
                                val json = local.text("${engine.host.base}/stream/tv/${Web.enc(row.id)}.json").text
                                val streams = codec.parseToJsonElement(json).obj().a("streams").map { it.obj().s("url") }
                                    .filter { it.startsWith("${engine.host.base}/hls/") }.take(2)
                                for (url in streams) {
                                    try {
                                        val segments = SegmentProbe(local).probe(StreamChoice("Live relay", url, mediaType = "hls"), true)
                                        if (segments.isNotEmpty()) return@withTimeout segments
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { }
                                }
                                emptyList()
                            }
                            if (verified.isNotEmpty()) {
                                val scope = if (row.channel) "continuous channel" else "started match"
                                write("PASS", scope, verified.sumOf { it.bytes }.toLong(), verified.map { it.format }.distinct())
                                println("$label: local Stremio relay PASS ($scope); ${verified.sumOf { it.bytes }} verified bytes; native playback NOT VERIFIED")
                                return@withTimeout
                            }
                        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Try the next independent published player. */ }
                    }
                    write("FAILED")
                    fail("$label did not relay verified media; see redacted live-relay report")
                }
            } finally { engine.shutdown() }
        }
    }
}
