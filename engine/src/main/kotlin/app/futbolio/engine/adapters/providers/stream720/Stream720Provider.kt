package app.futbolio.engine.adapters.providers.stream720

import app.futbolio.engine.*

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import app.futbolio.engine.adapters.providers.shared.StaticPlayerResolver

/** Static HTML/JSON/literal-data adapter. Never evaluates provider scripts. */
internal object Stream720Provider : Provider {
    override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
        val doc = net.text(source.url)
        return ProviderResult(parse(source, doc.text, doc.url).also { require(it.isNotEmpty()) { "No 720pstream football listings found" } }, activeUrl = doc.url)
    }
    internal fun parse(source: SourceConfig, html: String, base: String): List<Listing> {
        val doc = Jsoup.parse(html, base)
        val games = mutableMapOf<Pair<String, Long>, JsonObject>()
        for (payload in flightPayloads(doc)) {
            val field = Regex("\"initialGames\"\\s*:\\s*").find(payload) ?: continue
            val data = runCatching { LiteralReader(payload, field.range.last + 1).read() as? JsonArray }.getOrNull() ?: continue
            for (value in data.take(600)) {
                val game = value.obj()
                val slug = game.s("homeuri")
                if (!slug.matches(Regex("[a-z0-9-]{1,160}"))) continue
                val start = runCatching { Instant.parse(game.s("date")).toEpochMilli() }.getOrNull() ?: continue
                games[Web.resolve(base, "/live/soccer/$slug") to start] = game
            }
        }
        return doc.select("a.match-card[href]").take(600).mapNotNull { card ->
            runCatching {
                val page = Web.resolve(base, card.attr("href"))
                require(Web.url(page).path.startsWith("/live/soccer/"))
                val names = card.select(".team strong").map { it.text() }
                require(names.size == 2 && names.all(String::isNotBlank))
                val logos = card.select(".team img").map { runCatching { Web.resolve(base, it.attr("src")) }.getOrDefault("") }
                val start = runCatching { Instant.parse(card.selectFirst("time")?.attr("datetime")).toEpochMilli() }.getOrDefault(0)
                val metadata = games[page to start]
                val league = metadata?.s("description")?.substringBefore(", Playing at")?.trim().orEmpty().ifBlank { "Football" }
                Listing(source, names.joinToString(" vs "), league, page, starts = start,
                    homeLogo = logos.getOrElse(0) { "" }, awayLogo = logos.getOrElse(1) { "" })
            }.getOrNull()
        }.distinctBy { it.page }
    }

    private fun capture(text: String, regex: String) = Regex(regex).find(text)?.groupValues?.get(1)
        ?: error("720pstream player data layout changed")
    private fun decode(text: String): String {
        require(text.length <= 16_384)
        return Base64.getDecoder().decode(text).toString(Charsets.UTF_8)
    }
    private fun literal(text: String, name: String) = capture(text, """\b$name="([^"\r\n]+)"""")
    internal fun nestedPlayer(html: String, base: String): String {
        val tag = Jsoup.parse(html, base).selectFirst("script[data-pldm][data-pid][data-play][data-gc]") ?: error("Missing embed data")
        val config = codec.parseToJsonElement(capture(html, """const siteConfig\s*=\s*(\{[^;]+\});""")).obj()
        val host = tag.attr("data-pldm")
        require(host.matches(Regex("[a-zA-Z0-9.-]+")) && '.' in host)
        val category = tag.attr("data-gc").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        return "https://$host/sd0embed/$category".toHttpUrl().newBuilder().apply {
            addQueryParameter("pid", tag.attr("data-pid"))
            addQueryParameter("v", tag.attr("data-play"))
            addQueryParameter("player", tag.attr("data-player").ifBlank { "jw" })
            for ((param, key) in mapOf("csrf" to "csrf", "csrf_ip" to "csrf_ip", "expires" to "sec_expires", "sec_hash" to "sec_hash"))
                addQueryParameter(param, config.s(key).also { require(it.isNotBlank() && it.length <= 8192) })
        }.build().toString()
    }

    internal data class PlayerData(val media: String, val auth: String, val token: String, val device: String)
    internal fun playerData(html: String): PlayerData {
        val codes = capture(html, """F=d\(String\.fromCharCode\(\.\.\.\[([\d,]+)\]\)\)""")
        require(codes.length <= 32_768)
        val media = decode(codes.split(',').map { it.toInt().also { n -> require(n in 0..127) }.toChar() }.joinToString(""))
        val token = decode(capture(html, """\[m\]:d\("([A-Za-z0-9+/=]+)"\)"""))
        val authBase = decode(capture(html, """\bv=d\("([A-Za-z0-9+/=]+)"\)"""))
        val device = literal(html, "R")
        val auth = authBase.toHttpUrl().newBuilder().apply {
            addQueryParameter("scode", capture(html, """l=_\(\{scode:"([^"]+)""""))
            addQueryParameter("stream", literal(html, "k"))
            addQueryParameter("expires", capture(html, """y=parseInt\((\d+),10\)"""))
            addQueryParameter("u_id", device)
            addQueryParameter("session_id", capture(html, """l=_\(\{scode:"[^"]+",ts:y,session_id:"([^"]+)""""))
            addQueryParameter("host_id", capture(html, """host_id=\$\{encodeURIComponent\("([^"]+)"\)"""))
        }.build().toString()
        for (url in listOf(media, auth)) require(url.toHttpUrl().isHttps && url.toHttpUrl().username.isEmpty() && url.toHttpUrl().password.isEmpty())
        SourceHeaders(authOrigin = Web.origin(auth), authToken = token)
        return PlayerData(media, auth, token, device)
    }

    override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
        val page = net.text(item.page)
        val embed = pageEmbed(page.text, page.url) ?: error("Provider has not published a player for this event")
        val doc = net.text(embed, SourceHeaders(page.url))
        if (Jsoup.parse(doc.text).selectFirst("script[data-pldm][data-pid][data-play][data-gc]") == null) {
            return StaticPlayerResolver(net).resolve(item.copy(players = listOf(Player(doc.url))))
        }
        val player = nestedPlayer(doc.text, doc.url)
        val data = playerData(net.text(player, SourceHeaders(doc.url)).text)
        val session = Session(net, data, player)
        session.prepare()
        return listOf(StreamChoice(item.title, session.mediaUrl(), SourceHeaders(player, Web.origin(player)), mediaType = "hls", session = session))
    }

    /** Supports both server-rendered frames and the site's published Next.js player data. */
    internal fun pageEmbed(html: String, base: String): String? {
        val doc = Jsoup.parse(html, base)
        doc.selectFirst("iframe.video[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { return it }
        for (payload in flightPayloads(doc)) {
            // iframeUrl is the selected event's player prop. Raw iframeuri fields may
            // belong to other events in sidebars; never borrow another fixture's player.
            for (field in Regex("\"iframeUrl\"\\s*:\\s*").findAll(payload).take(8)) {
                val value = runCatching { LiteralReader(payload, field.range.last + 1).read() as? JsonPrimitive }.getOrNull()?.content ?: continue
                if (value.isBlank() || value == "null" || !(value.startsWith("/") || value.startsWith("https://") || value.startsWith("http://"))) continue
                runCatching { Web.resolve(base, value) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun flightPayloads(doc: org.jsoup.nodes.Document): List<String> =
        doc.select("script").take(96).mapNotNull { script ->
            val data = script.data()
            val marker = Regex("self\\.__next_f\\.push\\(\\s*").find(data) ?: return@mapNotNull null
            val chunk = runCatching { LiteralReader(data, marker.range.last + 1).read() as? JsonArray }.getOrNull() ?: return@mapNotNull null
            (chunk.getOrNull(1) as? JsonPrimitive)?.content
        }

    /** Renew on media traffic, not an unbounded background job; one renewal per session at a time. */
    internal class Session(private val net: Upstream, private val data: PlayerData, private val player: String,
        private val clock: () -> Long = System::currentTimeMillis) : PlaybackSession {
        override val id = opaqueToken()
        private val lock = Mutex()
        private var nextRefresh = 0L
        private var auth = data.auth
        @Volatile private var device = data.device
        private var generation = 0
        private val headers = SourceHeaders(player, Web.origin(player), authOrigin = Web.origin(data.auth), authToken = data.token)
        fun mediaUrl(): String = data.media.toHttpUrl().newBuilder().setQueryParameter("u_id", device).build().toString()
        suspend fun prepare() { ensure() }
        private suspend fun ensure(failedGeneration: Int? = null): Int = lock.withLock {
            if (generation > 0 && clock() < nextRefresh && (failedGeneration == null || failedGeneration != generation)) return@withLock generation
            val payload = withTimeout(15_000) { codec.parseToJsonElement(net.text(auth, headers).text).obj() }
            require(payload.b("success")) { "720pstream session unavailable" }
            val updatedDevice = payload.s("device_id").also { require(it.isNotBlank() && it.length <= 256) }
            val builder = auth.toHttpUrl().newBuilder().setQueryParameter("r", "1")
            for ((param, key) in mapOf("scode" to "scode", "expires" to "ts", "u_id" to "device_id", "session_id" to "session_id")) {
                val value = payload.s(key).also { require(it.isNotBlank() && it.length <= 4096) }
                builder.setQueryParameter(param, value)
            }
            val now = clock()
            val delay = payload.n("refresh_after").coerceIn(5, 300) * 1000
            val reauthorize = payload.n("reauthorize_at").takeIf { it > 0 }?.times(1000) ?: (now + delay)
            require(reauthorize > now) { "720pstream session expired" }
            nextRefresh = minOf(now + delay, reauthorize)
            auth = builder.build().toString(); device = updatedDevice; generation++
            generation
        }
        override suspend fun open(url: String, range: String?): UpstreamResponse {
            val version = ensure()
            fun target(): String {
                // Device bindings on CDN playlists follow the current session; keys retain their own URI.
                val parsed = url.toHttpUrl()
                return if (parsed.host == data.media.toHttpUrl().host && parsed.queryParameter("u_id") != null)
                    parsed.newBuilder().setQueryParameter("u_id", device).build().toString() else url
            }
            val response = net.open(target(), headers, range)
            if (response.status !in listOf(401, 403)) return response
            response.close()
            ensure(version)
            return net.open(target(), headers, range)
        }
    }
}
