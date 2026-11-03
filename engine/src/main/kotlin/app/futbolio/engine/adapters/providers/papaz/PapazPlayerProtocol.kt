package app.futbolio.engine.adapters.providers.papaz

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.engine.*
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Papaz's published VIP player literals and supported authentication request. */
internal class PapazPlayerProtocol(private val net: Upstream) {
    suspend fun resolve(player: Player, item: Listing): List<StreamChoice>? {
        if (player.target !in listOf("vip", "viptv")) return null
        val doc = net.text(player.url, SourceHeaders(item.page))
        val marker = Regex("(?:var|let|const)\\s+phptojs\\s*=\\s*").find(doc.text) ?: return emptyList()
        val config = LiteralReader(doc.text, marker.range.last + 1).read() as? JsonObject ?: return emptyList()
        val endpoint = Web.resolve(doc.url, config.s("stream_server_cdn_url").ifBlank { "/" } + "auth.php")
        val headers = SourceHeaders(doc.url, Web.origin(doc.url))
        val session = Session(net, endpoint, if (player.target == "viptv") "channel" else "id", player.source, headers, config.s("site_name"))
        session.prepare()
        val media = session.mediaUrl()
        return listOf(StreamChoice("", media, session.mediaHeaders(), session = session,
            mediaType = if (Regex("\\.(?:m3u8|js|css)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(media)) "hls" else ""))
    }

    /** The published player refreshes every 40 minutes. Renew on media traffic before that,
     * or once after 401/403; never spawn a polling job or cache a token in configuration. */
    internal class Session(
        private val net: Upstream, private val endpoint: String, private val field: String,
        private val source: String, private val headers: SourceHeaders, private val siteName: String,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ) : PlaybackSession {
        override val id = opaqueToken()
        private data class State(val url: String, val headers: SourceHeaders, val generation: Int, val until: Long)
        private val mutex = Mutex()
        @Volatile private var state: State? = null
        @Volatile private var roots: Set<String> = emptySet()
        suspend fun prepare() { ensure() }
        fun mediaUrl() = requireNotNull(state).url
        fun mediaHeaders() = requireNotNull(state).headers

        private suspend fun ensure(failedGeneration: Int? = null): State = mutex.withLock {
            val old = state
            if (old != null && clock() < old.until && (failedGeneration == null || failedGeneration != old.generation)) return@withLock old
            val payload = withTimeout(10_000) {
                net.postForm(endpoint, mapOf(field to source), headers).useResponse { response ->
                    if (response.status !in 200..299) throw java.io.IOException("Player request unavailable")
                    codec.parseToJsonElement(readLimited(response.body, 128 * 1024).toString(Charsets.UTF_8)).obj()
                }
            }
            val location = payload.s("URL").takeIf { it.isNotBlank() } ?: error("Provider has not published this stream")
            val url = Web.resolve(headers.referer, location)
            require(!url.contains(Regex("video_(?:generating|updating)|preload\\.mp4"))) { "Provider stream is not ready" }
            val extra = if (payload.s("TOKEN").isNotBlank()) mapOf("usertoken" to payload.s("TOKEN"), "pl" to siteName) else emptyMap()
            val next = State(url, SourceHeaders(headers.referer, headers.origin, extra), (old?.generation ?: 0) + 1, clock() + 35 * 60_000)
            roots = (roots + url).toList().takeLast(8).toSet()
            state = next
            next
        }

        override suspend fun open(url: String, range: String?): UpstreamResponse {
            val before = ensure()
            fun target(current: State) = if (url in roots) current.url else url
            val response = net.open(target(before), before.headers, range)
            if (response.status !in listOf(401, 403)) return response
            response.close()
            val after = ensure(before.generation)
            return net.open(target(after), after.headers, range)
        }
    }
}
