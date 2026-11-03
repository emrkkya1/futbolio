package app.futbolio.engine

import app.futbolio.application.ports.*

/** Bounded in-memory logo assets. Failed logos fall back to initials in platform renderers. */
class LogoCache(private val net: Upstream) {
    private data class Cached(val bytes: ByteArray?, val until: Long)
    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)
    suspend fun get(url: String): ByteArray? {
        if (url.isBlank()) return null
        synchronized(this) { cache[url]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.bytes } }
        val bytes = try {
            val uri = Web.url(url)
            require(uri.scheme == "https" && (uri.host == "a.espncdn.com" || uri.host.endsWith(".espncdn.com") || (uri.host == "im.mackolik.com" && uri.path.startsWith("/img/logo/"))))
            net.open(url).useResponse { r ->
                require(r.status in 200..299)
                readLimited(r.body, 256 * 1024).takeIf { it.size > 8 && (it[0] == 137.toByte() || (it[0] == 0xff.toByte() && it[1] == 0xd8.toByte()) || it.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        synchronized(this) {
            cache[url] = Cached(bytes, System.currentTimeMillis() + if (bytes == null) 5 * 60_000 else 24 * 3_600_000)
            while (cache.size > 32) cache.remove(cache.keys.first())
        }
        return bytes
    }
}
