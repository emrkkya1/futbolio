package app.futbolio.engine.adapters.probes

import app.futbolio.application.api.SegmentResult
import app.futbolio.application.ports.*
import app.futbolio.engine.*
import java.security.MessageDigest

/** Bounded transport/media-signature verification, distinct from native playback. */
internal class SegmentProbe(private val net: Upstream) : MediaProbe {
    override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean): List<SegmentResult> {
        suspend fun open(url: String) = choice.session?.open(url) ?: net.open(url, choice.headers)
        suspend fun playlist(url: String, depth: Int): List<SegmentResult> {
            require(depth <= 3) { "Playlist nesting limit" }
            val doc = open(url).useResponse { response ->
                require(response.status in 200..299) { "Playlist unavailable" }
                Document(response.url, readLimited(response.body, 512 * 1024).toString(Charsets.UTF_8))
            }
            require(doc.text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) { "Not an HLS playlist" }
            val lines = doc.text.lines().map(String::trim)
            val assets = lines.filter { it.isNotBlank() && !it.startsWith('#') }
            require(assets.isNotEmpty()) { "Empty HLS playlist" }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return playlist(Web.resolve(doc.url, assets.first()), depth + 1)
            val init = lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { Text.match(it, "URI=\"([^\"]+)\"") }?.takeIf(String::isNotBlank)
            val aesKey = lines.firstOrNull { it.startsWith("#EXT-X-KEY") && it.contains("METHOD=AES-128") }
            var currentKey: String? = null
            var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
            val assetKeys = mutableMapOf<String, Pair<String?, Long>>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY:")) currentKey = line
                else if (line.isNotBlank() && !line.startsWith('#')) assetKeys[Web.resolve(doc.url, line)] = currentKey to sequence++
            }
            val keyCache = mutableMapOf<String, ByteArray>()
            val keyUri = aesKey?.let { Text.match(it, "URI=\"([^\"]+)\"") }
            if (!verifyMedia && !keyUri.isNullOrBlank() && !keyUri.startsWith("data:")) open(Web.resolve(doc.url, keyUri)).useResponse { key ->
                require(key.status in 200..299 && readLimited(key.body, 32).size == 16) { "HLS encryption key unavailable" }
            }
            return (listOfNotNull(init) + assets.take(2)).map { Web.resolve(doc.url, it) }.map { asset -> open(asset).useResponse { response ->
                require(response.status in 200..299) { "Upstream HTTP ${response.status}" }
                val bytes = readLimited(response.body, 8 * 1024 * 1024)
                var media = bytes
                val (keyTag, number) = assetKeys[asset] ?: (null to 0L)
                var decrypted = false
                if (verifyMedia && keyTag != null && !keyTag.contains("METHOD=NONE")) {
                    require(keyTag.contains("METHOD=AES-128") && (!keyTag.contains("KEYFORMAT=") || keyTag.contains("KEYFORMAT=\"identity\""))) { "Unsupported media encryption" }
                    val keyUrl = Web.resolve(doc.url, Text.match(keyTag, "URI=\"([^\"]+)\""))
                    val key = keyCache[keyUrl] ?: open(keyUrl).useResponse { result ->
                        require(result.status in 200..299)
                        readLimited(result.body, 16).also { require(it.size == 16); keyCache[keyUrl] = it }
                    }
                    val explicit = Text.match(keyTag, "IV=0x([a-fA-F0-9]+)")
                    val iv = if (explicit.isEmpty()) java.nio.ByteBuffer.allocate(16).putLong(0).putLong(number).array()
                    else explicit.also { require(it.length <= 32) }.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    media = cipher.doFinal(bytes)
                    decrypted = true
                }
                val format = when {
                    media.size > 376 && listOf(0, 188, 376).all { media[it] == 0x47.toByte() } -> if (decrypted) "AES-128 → MPEG-TS" else "MPEG-TS"
                    media.size > 8 && media.copyOfRange(4, 8).toString(Charsets.US_ASCII) in listOf("ftyp", "styp", "moof", "sidx") -> if (decrypted) "AES-128 → MP4" else "MP4"
                    !verifyMedia && aesKey != null && bytes.size > 188 -> "AES-128 encrypted media"
                    else -> "unrecognized"
                }
                require(format != "unrecognized") { "Unrecognized media segment" }
                SegmentResult(bytes.size, format, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            } }
        }
        return playlist(choice.url, 0)
    }
}
