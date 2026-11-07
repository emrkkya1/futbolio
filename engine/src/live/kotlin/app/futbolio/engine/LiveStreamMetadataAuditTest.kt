package app.futbolio.engine

import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.engine.adapters.probes.HlsStreamAssessor
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.api.Configurations
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

/** Explicit online audit. Logs schema and allowlisted metadata only, never media URLs or credentials. */
class LiveStreamMetadataAuditTest {
    @Test fun inspectCurrentProviderMetadata() = runBlocking<Unit> {
        HttpUpstream().use { net ->
            val registry = ProviderRegistry()
            Configurations.sources().filter { it.enabled }.map { source -> async {
                val prefix = "AUDIT ${source.name}"
                try {
                    val traced = object : Upstream {
                        override suspend fun open(url: String, headers: SourceHeaders, range: String?) = net.open(url, headers, range)
                        override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders) = net.postForm(url, fields, headers)
                        override suspend fun text(url: String, headers: SourceHeaders): Document {
                            val doc = net.text(url, headers)
                            val json = runCatching { codec.parseToJsonElement(doc.text) }.getOrNull()
                            val shapes = linkedSetOf<String>()
                            fun shape(value: JsonElement, depth: Int) {
                                if (depth > 4 || shapes.size > 12) return
                                when (value) {
                                    is JsonObject -> {
                                        shapes += value.keys.sorted().joinToString(",")
                                        value.values.take(16).forEach { shape(it, depth + 1) }
                                    }
                                    is JsonArray -> value.take(2).forEach { shape(it, depth + 1) }
                                    else -> Unit
                                }
                            }
                            if (json != null) { shape(json, 0); println("$prefix JSON schemas=$shapes") }
                            return doc
                        }
                    }
                    val found = withTimeout(40_000) { DomainRecovery(traced, registry).discover(source) }
                    val now = System.currentTimeMillis()
                    val candidates = (found.listings.filter { !it.channel && MatchNormalizer.kickoff(it, now).let { start -> start > 0 && now - start in -900_000..7_200_000 } } +
                        found.listings.filter { it.channel } + found.listings).distinctBy { it.id }.take(3)
                    println("$prefix listings=${found.listings.size}; candidates=${candidates.map { Text.plain(it.title).take(80) }}")
                    for (row in candidates) {
                        val choices = withTimeoutOrNull(25_000) { registry.get(source.kind).resolve(row, traced) }.orEmpty().filter { it.url.isNotBlank() }
                        println("$prefix resolved=${choices.size}; playerLabels=${row.players.take(4).map { Text.plain(it.label).take(60) to Text.plain(it.language).take(20) }}")
                        if (choices.isEmpty()) continue
                        for (choice in choices.take(2)) {
                            println("$prefix choice=${Text.plain(choice.title).take(120)}; hls=${isHls(choice)}; session=${choice.session != null}")
                            if (!isHls(choice)) continue
                            withTimeoutOrNull(15_000) {
                                suspend fun inspect(url: String, depth: Int) {
                                    if (depth > 3) return
                                    val doc = (choice.session?.open(url) ?: net.open(url, choice.headers)).useResponse {
                                        println("$prefix playlist depth=$depth; http=${it.status}; type=${it.header("content-type")}")
                                        Document(it.url, readLimited(it.body, 512 * 1024).toString(Charsets.UTF_8))
                                    }
                                    val lines = doc.text.lines().map(String::trim)
                                    val tags = lines.filter { it.startsWith('#') }.groupingBy { it.substringBefore(':') }.eachCount()
                                    println("$prefix playlist tags=$tags")
                                    val attributes = listOf("BANDWIDTH", "AVERAGE-BANDWIDTH", "RESOLUTION", "FRAME-RATE", "CODECS", "LANGUAGE", "NAME")
                                    lines.filter { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-MEDIA:") }.take(8).forEach { line ->
                                        val safe = attributes.mapNotNull { key -> Regex("(?:[:,])$key=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.take(100)?.let { key to it } }.toMap()
                                        println("$prefix rendition=$safe")
                                    }
                                    println("$prefix durations=${lines.filter { it.startsWith("#EXTINF:") }.takeLast(3).map { it.substringAfter(':').substringBefore(',') }}; target=${lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')}; keyMethods=${lines.filter { it.startsWith("#EXT-X-KEY:") }.map { Text.match(it, "METHOD=([^,]+)") }.distinct()}")
                                    if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
                                        lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') }?.let { inspect(Web.resolve(doc.url, it), depth + 1) }
                                    } else {
                                        lines.lastOrNull { it.isNotBlank() && !it.startsWith('#') }?.let { asset ->
                                            val target = Web.resolve(doc.url, asset)
                                            (choice.session?.open(target, "bytes=0-524287") ?: net.open(target, choice.headers, "bytes=0-524287")).useResponse {
                                                println("$prefix ranged segment http=${it.status}; length=${it.header("content-length")}; range=${it.header("content-range")}; encoding=${it.header("content-encoding")}; type=${it.header("content-type")}")
                                                withContext(Dispatchers.IO) { val bytes = ByteArray(8192); it.body.read(bytes) }
                                            }
                                        }
                                    }
                                }
                                inspect(choice.url, 0)
                            }
                            val start = System.nanoTime()
                            var latest = StreamAssessment()
                            val assessment = withTimeoutOrNull(6000) { HlsStreamAssessor(net).assess(choice) { latest = it } } ?: latest
                            println("$prefix assessment=$assessment; elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
                        }
                        break
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    println("$prefix failed=${failure.javaClass.simpleName}")
                }
            } }.awaitAll()
        }
    }
}
