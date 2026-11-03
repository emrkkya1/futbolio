package app.futbolio.engine

import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig

import java.io.ByteArrayInputStream
import java.io.IOException

/** Deterministic provider + HLS fixture, never contacts stream sites. Shared with Android tests. */
class FixtureUpstream : Upstream {
    val source = SourceConfig("taraftarium", "taraftarium", "Fixture", "https://fixture.example/")
    val segment = ByteArray(1024 * 1024) { (it % 251).toByte() }.also { b -> for (i in b.indices step 188) b[i] = 0x47 }
    var offline = false
    var lastHeaders = SourceHeaders()
    var calls = 0
    override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
        calls++; lastHeaders = headers
        if (offline) throw IOException("Upstream HTTP 503")
        fun text(body: String) = UpstreamResponse(200, url, mapOf("content-type" to "text/html"), ByteArrayInputStream(body.toByteArray()))
        return when {
            url == source.url -> text("<a href='/channel.html?id=patron'><b>BeIN Sports 1</b> 7/24</a>")
            url.contains("channel.html") -> text("<meta name='referrer' content='origin'><script>const CONFIG={baseUrl:'https://cdn.example/'};return CONFIG.baseUrl+b+'/mono.m3u8';</script>")
            url.endsWith(".m3u8") -> text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\nsegment.jpg\n")
            url.endsWith("key.bin") -> UpstreamResponse(200, url, emptyMap(), ByteArrayInputStream(ByteArray(16)))
            url.endsWith("segment.jpg") -> {
                val bytes = if (range == null) segment else segment.copyOf(564)
                val h = mutableMapOf("content-type" to "image/jpeg", "content-length" to bytes.size.toString())
                if (range != null) h["content-range"] = "bytes 0-563/${segment.size}"
                UpstreamResponse(if (range == null) 200 else 206, url, h, ByteArrayInputStream(bytes))
            }
            else -> UpstreamResponse(404, url, emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
    }
}
