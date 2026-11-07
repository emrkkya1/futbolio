package app.futbolio.engine.adapters.probes

import app.futbolio.application.ports.*
import app.futbolio.engine.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

/** Two recent segments, at most 512 KiB each; uses the same authenticated transport as playback. */
internal class HlsStreamAssessor(private val net: Upstream, private val nanoTime: () -> Long = System::nanoTime) : StreamAssessor {
    override suspend fun assess(choice: StreamChoice): StreamAssessment {
        return assess(choice) {}
    }

    override suspend fun assess(choice: StreamChoice, update: (StreamAssessment) -> Unit): StreamAssessment {
        suspend fun open(url: String, range: String? = null) = choice.session?.open(url, range) ?: net.open(url, choice.headers, range)
        var metadata = StreamAssessment()
        suspend fun playlist(url: String, depth: Int): StreamAssessment {
            require(depth <= 3) { "Playlist nesting limit" }
            val doc = open(url).useResponse {
                require(it.status in 200..299)
                Document(it.url, readLimited(it.body, 512 * 1024).toString(Charsets.UTF_8))
            }
            require(doc.text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U"))
            val lines = doc.text.lines().map(String::trim)
            fun attribute(line: String, key: String): String =
                Regex("(?:[:,])${Regex.escape(key)}=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"').orEmpty()
            val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }.mapNotNull { index ->
                val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: return@mapNotNull null
                lines[index] to uri
            }
            if (variants.isNotEmpty()) {
                // Test the highest advertised bitrate, rather than recommending a fast low rendition
                // while the player might select a much more demanding one.
                val (tag, uri) = variants.maxBy { attribute(it.first, "BANDWIDTH").toLongOrNull() ?: 0 }
                metadata = metadata.copy(
                    adaptive = variants.size > 1 || metadata.adaptive,
                    resolution = attribute(tag, "RESOLUTION").takeIf { it.matches(Regex("\\d{1,5}x\\d{1,5}")) }.orEmpty(),
                    frameRate = attribute(tag, "FRAME-RATE").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 240 },
                    bitrate = attribute(tag, "BANDWIDTH").toLongOrNull()?.takeIf { it > 0 },
                    codecs = attribute(tag, "CODECS").takeIf { it.length <= 160 && it.matches(Regex("[A-Za-z0-9., _-]+")) }.orEmpty(),
                )
                update(metadata)
                return playlist(Web.resolve(doc.url, uri), depth + 1)
            }
            // Byte-range playlists need explicit ranges; do not benchmark the whole shared object.
            if (lines.any { it.startsWith("#EXT-X-BYTERANGE:") || it.startsWith("#EXT-X-GAP") }) return metadata
            data class Asset(val url: String, val duration: Double, val key: String?, val sequence: Long)
            val segments = mutableListOf<Asset>()
            var duration: Double? = null
            var currentKey: String? = null
            var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY:")) currentKey = line.takeUnless { attribute(it, "METHOD") == "NONE" }
                else if (line.startsWith("#EXTINF:")) duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                else if (line.isNotEmpty() && !line.startsWith('#')) {
                    duration?.takeIf { it.isFinite() && it > 0 }?.let { segments += Asset(Web.resolve(doc.url, line), it, currentKey, sequence) }
                    sequence++
                    duration = null
                }
            }
            val sampled = segments.takeLast(2)
            val keys = mutableMapOf<String, ByteArray>()
            // Validate the actual sampled key bindings, including key rotation and METHOD=NONE.
            for (tag in sampled.mapNotNull { it.key }.distinct()) {
                if (attribute(tag, "METHOD") != "AES-128" ||
                    attribute(tag, "KEYFORMAT").let { it.isNotEmpty() && it != "identity" }) return metadata
                val key = attribute(tag, "URI")
                if (key.startsWith("data:")) return metadata
                require(key.isNotBlank())
                keys[tag] = open(Web.resolve(doc.url, key)).useResponse {
                    require(it.status == 200)
                    readLimited(it.body, 16).also { bytes -> require(bytes.size == 16) }
                }
            }
            val ratios = mutableListOf<Double>()
            for (asset in sampled) {
                val seconds = asset.duration
                val start = nanoTime()
                var headerSeconds = 0.0
                var transferSeconds = 0.0
                var totalBytes: Long? = null
                val (bytes, complete) = open(asset.url, "bytes=0-524287").useResponse { response ->
                    headerSeconds = (nanoTime() - start) / 1_000_000_000.0
                    require(response.status == 200 || response.status == 206)
                    require(response.header("content-encoding").let { it.isBlank() || it == "identity" })
                    var expectedBytes: Long? = null
                    totalBytes = if (response.status == 206) {
                        val range = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(response.header("content-range"))
                        require(range != null) { "Invalid partial segment" }
                        val end = range.groupValues[1].toLong()
                        expectedBytes = end + 1
                        range.groupValues[2].toLong().also { require(end < it && end < 512 * 1024) }
                    } else response.header("content-length").toLongOrNull()?.takeIf { it > 0 }.also { expectedBytes = it }
                    withContext(Dispatchers.IO) {
                        val buffer = ByteArray(8192)
                        var count = 0
                        var eof = false
                        val prefix = java.io.ByteArrayOutputStream(64 * 1024)
                        while (count < 512 * 1024) {
                            coroutineContext.ensureActive()
                            val read = response.body.read(buffer, 0, minOf(buffer.size, 512 * 1024 - count))
                            if (read < 0) { eof = true; break }
                            if (prefix.size() < 64 * 1024) prefix.write(buffer, 0, minOf(read, 64 * 1024 - prefix.size()))
                            count += read
                        }
                        // Parsing/decryption and closing an intentionally partial response are
                        // probe work, not network transfer time.
                        transferSeconds = ((nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(.001)
                        var signature = prefix.toByteArray()
                        asset.key?.let { tag ->
                            val explicit = attribute(tag, "IV").removePrefix("0x")
                            val iv = if (explicit.isEmpty()) java.nio.ByteBuffer.allocate(16).putLong(0).putLong(asset.sequence).array()
                            else {
                                require(explicit.matches(Regex("[0-9a-fA-F]{1,32}")))
                                explicit.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            }
                            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keys.getValue(tag), "AES"), javax.crypto.spec.IvParameterSpec(iv))
                            signature = cipher.doFinal(signature.copyOf(signature.size - signature.size % 16))
                        }
                        require(
                            (signature.size > 376 && listOf(0, 188, 376).all { signature[it] == 0x47.toByte() }) ||
                            (signature.size > 8 && signature.copyOfRange(4, 8).toString(Charsets.US_ASCII) in listOf("ftyp", "styp", "moof", "sidx"))
                        ) { "Not a recognized video segment" }
                        H264SegmentMetadata.dimensions(signature)?.let { dimensions ->
                            metadata = metadata.copy(resolution = dimensions.toString(), resolutionMeasured = true,
                                codecs = metadata.codecs.ifBlank { "H.264" })
                        }
                        require(!eof || expectedBytes == null || count.toLong() == expectedBytes) { "Truncated segment" }
                        count to ((eof && response.status == 200) || totalBytes == count.toLong())
                    }
                }
                require(bytes > 0)
                val elapsed = transferSeconds
                // Request/TLS/auth latency happens once, not once per extrapolated byte range.
                // Scaling total elapsed by segment/sample size can label a fast high-RTT path slow.
                val payloadSeconds = (elapsed - headerSeconds).coerceAtLeast(.001)
                fun estimatedRatio(size: Double) = seconds / (headerSeconds + payloadSeconds * size / bytes)
                val ratio = if (complete) seconds / elapsed
                    else totalBytes?.takeIf { it >= bytes && bytes >= 32 * 1024 }?.let { estimatedRatio(it.toDouble()) }
                        ?: metadata.bitrate?.takeIf { !metadata.bitrateEstimated && bytes >= 32 * 1024 }?.let { estimatedRatio(maxOf(bytes.toDouble(), it * seconds / 8)) }
                if (ratio != null) ratios += ratio
                if (metadata.bitrate == null || metadata.bitrateEstimated) {
                    (totalBytes ?: bytes.toLong().takeIf { complete })?.let { size ->
                        metadata = metadata.copy(bitrate = (size * 8 / seconds).toLong(), bitrateEstimated = true)
                    }
                }
                val headroom = ratios.minOrNull()
                val status = when {
                    headroom == null || ratios.size < 2 -> ConnectionStatus.UNKNOWN
                    headroom < 1.0 -> ConnectionStatus.SLOW
                    headroom < 2.0 -> ConnectionStatus.MEDIUM
                    else -> ConnectionStatus.GOOD
                }
                metadata = metadata.copy(status = status, headroom = headroom, samples = ratios.size,
                    headroomEstimated = metadata.headroomEstimated || !complete)
                update(metadata)
            }
            return metadata
        }
        return try { playlist(choice.url, 0) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { metadata.copy(status = ConnectionStatus.UNAVAILABLE, headroom = null, samples = 0) }
    }
}
