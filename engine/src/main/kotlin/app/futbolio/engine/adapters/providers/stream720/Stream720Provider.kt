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
import kotlinx.serialization.json.JsonElement
import app.futbolio.engine.adapters.providers.shared.StaticPlayerResolver
import app.futbolio.engine.adapters.providers.shared.StaticPlayerData

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
        val compact = text.filterNot(Char::isWhitespace)
        return runCatching { Base64.getDecoder().decode(compact) }.getOrElse { Base64.getUrlDecoder().decode(compact) }
            .toString(Charsets.UTF_8)
    }
    private fun literal(text: String, name: String) = capture(text, """\b$name="([^"\r\n]+)"""")
    internal fun nestedPlayer(html: String, base: String): String {
        val tag = Jsoup.parse(html, base).selectFirst("script[data-pldm][data-pid][data-play][data-gc]") ?: error("Missing embed data")
        val config = StaticPlayerData.objects(html).filter { value ->
            listOf("csrf", "csrf_ip", "sec_expires", "sec_hash").all { value.s(it).isNotBlank() }
        }.singleOrNull() ?: error("Missing or ambiguous embed configuration")
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

    internal data class PlayerData(val media: String, val auth: String, val token: String, val device: String) {
        override fun toString() = "PlayerData(credentials=[redacted])"
    }

    private fun fieldKey(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    private fun field(config: JsonObject, vararg names: String): JsonElement? {
        val keys = names.map(::fieldKey).toSet()
        val values = config.filterKeys { fieldKey(it) in keys }.values.distinct()
        require(values.size <= 1) { "Ambiguous player field" }
        return values.singleOrNull()
    }

    /** Detect the protocol's data shape, not its global name or minified local identifiers. */
    private fun publishedPlayer(html: String): JsonObject? {
        val candidates = StaticPlayerData.objects(html).filter { config ->
            val keys = config.keys.map(::fieldKey).toSet()
            listOf(setOf("urlsource", "sourceurl", "mediaurl"), setOf("sectokenurl", "authurl"),
                setOf("deviceid"), setOf("strunqid", "streamid")).all { aliases -> keys.any { it in aliases } }
        }
        if (candidates.size <= 1) return candidates.singleOrNull()
        val selectedIds = Jsoup.parse(html).select("script[data-player-id]").map { it.attr("data-player-id") }.distinct()
        return candidates.filter { (field(it, "playerId") as? JsonPrimitive)?.content in selectedIds }.singleOrNull()
            ?: error("Ambiguous published player configurations")
    }

    internal fun playerData(html: String): PlayerData = playerData(html, publishedPlayer(html))

    private fun playerData(html: String, config: JsonObject?): PlayerData {
        if (config != null) {
            fun required(vararg names: String) = (field(config, *names) as? JsonPrimitive)?.content.orEmpty()
                .also { require(it.isNotBlank() && it.length <= 4096) { "Missing player field" } }
            fun url(vararg names: String): String {
                val element = field(config, *names)
                val value = if (element is JsonArray) {
                    require(element.size <= 16_384)
                    element.joinToString("") { char -> (char as JsonPrimitive).content.toInt().also { require(it in 0..127) }.toChar().toString() }
                } else required(*names)
                return if (value.startsWith("https://", true) || value.startsWith("http://", true)) value else decode(value)
            }
            val header = (field(config, "tokenName") as? JsonPrimitive)?.content.orEmpty().ifBlank { "X-CSRF-Auth" }
            require(header.equals("X-CSRF-Auth", ignoreCase = true))
            val media = url("urlSource", "sourceUrl", "mediaUrl")
            val auth = url("secTokenUrl", "authUrl").toHttpUrl().newBuilder().apply {
                addQueryParameter("scode", required("sCode"))
                addQueryParameter("stream", required("strUnqId", "streamId"))
                addQueryParameter("expires", required("expireTs", "expires"))
                addQueryParameter("u_id", required("device_id", "deviceId"))
                addQueryParameter("session_id", required("session_id", "sessionId"))
                addQueryParameter("host_id", required("edgeHostId", "hostId"))
            }.build().toString()
            return validatedPlayerData(media, auth, decode(required("csrf_token", "csrfToken")), required("device_id", "deviceId"))
        }
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
        return validatedPlayerData(media, auth, token, device)
    }

    private fun validatedPlayerData(media: String, auth: String, token: String, device: String): PlayerData {
        for (url in listOf(media, auth)) {
            Web.url(url)
            require(url.toHttpUrl().isHttps && url.toHttpUrl().username.isEmpty() && url.toHttpUrl().password.isEmpty())
        }
        SourceHeaders(authOrigin = Web.origin(auth), authToken = token)
        return PlayerData(media, auth, token, device)
    }

    override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
        val page = net.text(item.page)
        val embed = pageEmbed(page.text, page.url) ?: error("Provider has not published a player for this event")
        val doc = net.text(embed, SourceHeaders(page.url))
        val direct = publishedPlayer(doc.text)
        if (direct != null) return sessionChoice(item, net, playerData(doc.text, direct), doc.url)
        if (Jsoup.parse(doc.text).selectFirst("script[data-pldm][data-pid][data-play][data-gc]") == null) {
            return StaticPlayerResolver(net).resolve(item.copy(players = listOf(Player(doc.url))))
        }
        val player = nestedPlayer(doc.text, doc.url)
        val data = playerData(net.text(player, SourceHeaders(doc.url)).text)
        return sessionChoice(item, net, data, player)
    }

    private suspend fun sessionChoice(item: Listing, net: Upstream, data: PlayerData, player: String): List<StreamChoice> {
        val session = Session(net, data, player)
        session.prepare()
        return listOf(StreamChoice(item.title, session.mediaUrl(), SourceHeaders(player, Web.origin(player)), mediaType = "hls", session = session))
    }

    /** Supports both server-rendered frames and the site's published Next.js player data. */
    internal fun pageEmbed(html: String, base: String): String? {
        val doc = Jsoup.parse(html, base)
        val frames = doc.select("iframe.video[src], #player iframe[src], iframe[data-player][src]")
            .mapNotNull { runCatching { Web.resolve(base, it.attr("src")) }.getOrNull() }.distinct()
        if (frames.size == 1) return frames.single()
        val payloads = flightPayloads(doc)
        for (payload in payloads) {
            // iframeUrl is the selected event's player prop. Raw iframeuri fields may
            // belong to other events in sidebars; never borrow another fixture's player.
            for (field in Regex("\"iframeUrl\"\\s*:\\s*").findAll(payload).take(8)) {
                val value = runCatching { LiteralReader(payload, field.range.last + 1).read() as? JsonPrimitive }.getOrNull()?.content ?: continue
                if (value.isBlank() || value == "null" || !(value.startsWith("/") || value.startsWith("https://") || value.startsWith("http://"))) continue
                runCatching { Web.resolve(base, value) }.getOrNull()?.let { return it }
            }
        }
        // A null selected-event prop is not permission to substitute an advertisement/sidebar.
        if (payloads.isNotEmpty()) return null
        return doc.select("iframe[src]").mapNotNull { runCatching { Web.resolve(base, it.attr("src")) }.getOrNull() }
            .distinct().singleOrNull()
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
