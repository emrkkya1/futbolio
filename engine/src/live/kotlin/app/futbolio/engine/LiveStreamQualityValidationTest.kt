package app.futbolio.engine

import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.engine.adapters.probes.HlsStreamAssessor
import app.futbolio.engine.adapters.probes.H264SegmentMetadata
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.api.Configurations
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opt-in, sequential validation: short production snapshot versus independent full transfers.
 * This establishes transport evidence, NOT player rebuffer/decode correctness. No source URLs logged.
 */
class LiveStreamQualityValidationTest {
    private data class Asset(val url: String, val seconds: Double, val key: String?, val sequence: Long)
    private data class Media(val url: String, val lines: List<String>, val assets: List<Asset>)
    private val records = mutableListOf<JsonObject>()
    private suspend fun open(net: Upstream, choice: StreamChoice, url: String) = choice.session?.open(url) ?: net.open(url, choice.headers)
    private fun attribute(tag: String, name: String) = Regex("(?:[:,])${Regex.escape(name)}=(\"[^\"]*\"|[^,]*)")
        .find(tag)?.groupValues?.get(1)?.trim('"').orEmpty()

    private suspend fun media(net: Upstream, choice: StreamChoice): Media {
        var url = choice.url
        repeat(4) {
            val doc = open(net, choice, url).useResponse {
                require(it.status == 200)
                Document(it.url, readLimited(it.body, 512 * 1024).toString(Charsets.UTF_8))
            }
            require(doc.text.trimStart().startsWith("#EXTM3U"))
            val lines = doc.text.lines().map(String::trim)
            val variants = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF:") }
            if (variants.isNotEmpty()) {
                val variant = variants.maxBy { attribute(it.value, "BANDWIDTH").toLongOrNull() ?: 0L }
                url = Web.resolve(doc.url, lines.drop(variant.index + 1).first { it.isNotBlank() && !it.startsWith('#') })
            } else {
                require(lines.none { it.startsWith("#EXT-X-BYTERANGE:") || it.startsWith("#EXT-X-GAP") })
                var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
                var key: String? = null
                var duration: Double? = null
                val assets = mutableListOf<Asset>()
                for (line in lines) {
                    if (line.startsWith("#EXT-X-KEY:")) key = line.takeUnless { attribute(it, "METHOD") == "NONE" }
                    else if (line.startsWith("#EXTINF:")) duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    else if (line.isNotBlank() && !line.startsWith('#')) {
                        duration?.takeIf { it > 0 && it.isFinite() }?.let { assets += Asset(Web.resolve(doc.url, line), it, key, sequence) }
                        duration = null
                        sequence++
                    }
                }
                require(assets.isNotEmpty())
                return Media(doc.url, lines, assets)
            }
        }
        error("Playlist nesting limit")
    }

    private suspend fun decrypt(net: Upstream, choice: StreamChoice, media: Media, asset: Asset, bytes: ByteArray): ByteArray {
        val tag = asset.key ?: return bytes
        require(attribute(tag, "METHOD") == "AES-128")
        require(attribute(tag, "KEYFORMAT").let { it.isEmpty() || it == "identity" })
        val key = open(net, choice, Web.resolve(media.url, attribute(tag, "URI"))).useResponse {
            require(it.status == 200); readLimited(it.body, 16).also { bytes -> require(bytes.size == 16) }
        }
        val explicit = attribute(tag, "IV").removePrefix("0x")
        val iv = if (explicit.isEmpty()) java.nio.ByteBuffer.allocate(16).putLong(0).putLong(asset.sequence).array()
        else explicit.also { require(it.matches(Regex("[0-9a-fA-F]{1,32}"))) }.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }

    /** Test-only ffprobe over a local TS file; never ships as an Android/desktop dependency. */
    private fun video(bytes: ByteArray): JsonObject? {
        val path = Files.createTempFile(Path.of("/tmp/opencode"), "futbolio-quality-validation-", ".ts")
        try {
            Files.write(path, bytes)
            val start = System.nanoTime()
            val process = ProcessBuilder(System.getenv("FUTBOLIO_FFPROBE") ?: "ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-f", "mpegts",
                "-probesize", "${bytes.size}", "-analyzeduration", "1000000", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,r_frame_rate", "-of", "json", path.toString()).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) return buildJsonObject { put("error", "PROBE_TIMEOUT") }
                val output = process.inputStream.readNBytes(32 * 1024).toString(Charsets.UTF_8)
                val jsonStart = output.indexOf('{')
                val result = runCatching { codec.parseToJsonElement(output.substring(jsonStart)).obj() }.getOrNull()
                    ?: return buildJsonObject {
                        put("error", if (output.contains("Unable to create decoder")) "LOCAL_DECODER_UNAVAILABLE" else "NO_JSON")
                        put("exit", process.exitValue())
                    }
                val stream = result.a("streams").firstOrNull()?.obj()
                    ?: return buildJsonObject { put("error", "NO_VIDEO_STREAM"); put("exit", process.exitValue()) }
                if (stream.n("width") <= 0 || stream.n("height") <= 0) return buildJsonObject {
                    put("error", "NO_DIMENSIONS"); put("codec", stream.s("codec_name")); put("exit", process.exitValue())
                }
                return buildJsonObject {
                    put("resolution", "${stream.n("width")}x${stream.n("height")}")
                    put("codec", stream.s("codec_name")); put("fps", stream.s("r_frame_rate"))
                    put("parseMillis", (System.nanoTime() - start) / 1_000_000)
                }
            } finally { process.destroyForcibly() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun validateSnapshotsAgainstSequentialFullSegments() = runBlocking<Unit> {
        val registry = ProviderRegistry()
        val providerFilter = System.getenv("FUTBOLIO_VALIDATION_PROVIDER").orEmpty()
        val maxChoices = System.getenv("FUTBOLIO_VALIDATION_CHOICES")?.toIntOrNull()?.coerceIn(1, 2) ?: 1
        HttpUpstream().use { net ->
            // Deliberately sequential: competing probes must not divide available bandwidth.
            for (source in Configurations.sources().filter { it.enabled && (providerFilter.isBlank() || it.kind == providerFilter) }) {
                try {
                    val discovered = withTimeout(40_000) { DomainRecovery(net, registry).discover(source) }
                    val now = System.currentTimeMillis()
                    val candidates = (discovered.listings.filter { !it.channel && now - MatchNormalizer.kickoff(it, now) in 0..7_200_000 } +
                        discovered.listings.filter { it.channel }).distinctBy { it.id }.take(4)
                    var tested = false
                    for (row in candidates) {
                        val resolutionStart = System.nanoTime()
                        val choices = withTimeoutOrNull(25_000) { registry.get(source.kind).resolve(row, net) }.orEmpty().filter { it.url.isNotBlank() && isHls(it) }
                        val resolutionMillis = (System.nanoTime() - resolutionStart) / 1_000_000
                        if (choices.isEmpty()) continue
                        for ((index, choice) in choices.take(maxChoices).withIndex()) {
                            val started = System.nanoTime()
                            var latest = StreamAssessment()
                            val snapshot = withTimeoutOrNull(6000) { HlsStreamAssessor(net).assess(choice) { latest = it } } ?: latest
                            val snapshotMillis = (System.nanoTime() - started) / 1_000_000
                            val ratios = mutableListOf<Double>()
                            val seen = mutableSetOf<String>()
                            val transfers = mutableListOf<JsonObject>()
                            var metadata: JsonObject? = null
                            var measuredBitrate: Long? = null
                            for (sample in 0..2) {
                                try {
                                    withTimeout(18_000) {
                                        val playlist = media(net, choice)
                                        val asset = playlist.assets.takeLast(3).firstOrNull { it.url !in seen } ?: return@withTimeout
                                        seen += asset.url
                                        val start = System.nanoTime()
                                        val bytes = open(net, choice, asset.url).useResponse {
                                            require(it.status == 200)
                                            val expected = it.header("content-length").toLongOrNull()
                                            readLimited(it.body, 8 * 1024 * 1024).also { bytes -> require(expected == null || expected == bytes.size.toLong()) }
                                        }
                                        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                                        require(bytes.isNotEmpty())
                                        val ratio = asset.seconds / seconds
                                        ratios += ratio
                                        measuredBitrate = (bytes.size * 8.0 / asset.seconds).toLong()
                                        transfers += buildJsonObject {
                                            put("bytes", bytes.size); put("mediaSeconds", asset.seconds)
                                            put("downloadMillis", (seconds * 1000).toLong()); put("headroom", ratio)
                                            put("segmentBitrate", measuredBitrate)
                                        }
                                        if (metadata == null) {
                                            val clear = decrypt(net, choice, playlist, asset, bytes)
                                            metadata = buildJsonObject {
                                                put("full", video(clear) ?: JsonNull)
                                                val headerStart = System.nanoTime()
                                                put("headerResolution", H264SegmentMetadata.dimensions(clear)?.toString().orEmpty())
                                                put("headerParseMicros", (System.nanoTime() - headerStart) / 1000)
                                                for (size in listOf(16 * 1024, 64 * 1024, 512 * 1024)) {
                                                    put("prefix$size", video(clear.copyOfRange(0, minOf(size, clear.size))) ?: JsonNull)
                                                    put("headerResolution$size", H264SegmentMetadata.dimensions(clear.copyOfRange(0, minOf(size, clear.size)))?.toString().orEmpty())
                                                }
                                            }
                                        }
                                    }
                                } catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    transfers += buildJsonObject { put("failure", failure.javaClass.simpleName) }
                                }
                            }
                            val baseline = when {
                                ratios.size < 3 -> "INCONCLUSIVE"
                                ratios.count { it < 1.0 } >= 2 -> "REPEATED_SLOW"
                                ratios.all { it >= 2.0 } -> "CONSISTENT_HEADROOM"
                                else -> "MIXED_OR_BORDERLINE"
                            }
                            val record = buildJsonObject {
                                put("provider", source.name); put("choiceIndex", index)
                                put("providerResolutionMillis", resolutionMillis); put("snapshotMillis", snapshotMillis)
                                put("snapshot", snapshot.status.name); put("snapshotSamples", snapshot.samples)
                                snapshot.headroom?.let { put("snapshotHeadroom", it) }
                                snapshot.bitrate?.let { put("snapshotBitrate", it) }
                                put("snapshotResolution", snapshot.resolution)
                                put("baseline", baseline); put("transfers", JsonArray(transfers)); put("video", metadata ?: JsonNull)
                                put("potentialFalseGood", snapshot.status == ConnectionStatus.GOOD && baseline == "REPEATED_SLOW")
                                put("potentialFalseBad", snapshot.status == ConnectionStatus.SLOW && baseline == "CONSISTENT_HEADROOM")
                                put("nativePlaybackVerified", false)
                            }
                            records += record
                            println("QUALITY $record")
                            tested = true
                        }
                        if (tested) break
                    }
                    if (!tested) println("QUALITY ${source.name}: no playable current candidate")
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    println("QUALITY ${source.name}: ${failure.javaClass.simpleName}")
                }
            }
        }
        val path = Path.of("build/reports/live-quality-validation-${System.currentTimeMillis()}.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(records)))
        println("QUALITY report=$path; streams=${records.size}; nativePlaybackVerified=false")
        check(records.any { it.s("baseline") != "INCONCLUSIVE" }) { "No complete full-segment baseline; do not claim validation succeeded" }
    }
}
