package app.futbolio.engine.adapters.providers.futbolvivo

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.StaticPlayerResolver
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneId

internal object FutbolVivoProvider : Provider {
    override suspend fun resolve(item: Listing, net: Upstream) = StaticPlayerResolver(net).resolve(item)

    override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
        val home = net.text(source.url)
        val api = source.apiUrl.ifBlank { Web.resolve(home.url, "/api/agenda") }
        val matches = parse(source.copy(url = home.url), codec.parseToJsonElement(net.text(api, SourceHeaders(home.url)).text).obj())
        val channels = Text.anchors(home.text, home.url).filter { Web.url(it.first).path.startsWith("/en-vivo/") }
            .distinctBy { it.first }.take(100).mapNotNull { (url, name) ->
                val title = name.replace(Regex("(?i)\\s+(?:en vivo|ver canal|deportes en vivo).*$"), "").trim()
                runCatching { Listing(source, title, "Sports channels", url, ListingKind.CHANNEL) }.getOrNull()
            }
        require(matches.isNotEmpty() || channels.isNotEmpty()) { "No FutbolVivo listings found" }
        return ProviderResult((matches + channels).take(600), activeUrl = home.url)
    }

    fun parse(source: SourceConfig, payload: JsonObject): List<Listing> {
        require(payload["events"] is JsonArray) { "FutbolVivo agenda format changed" }
        return payload.a("events").take(600).mapNotNull { value ->
            val row = value.obj()
            if (Text.norm(row.s("sport")) !in listOf("futbol", "football", "soccer", "futebol")) return@mapNotNull null
            // The agenda currently labels some NHL/NFL fixtures as "futbol" too.
            if (Text.otherSport(row.s("title"))) return@mapNotNull null
            runCatching {
                val kickoff = LocalDateTime.parse("${row.s("date")}T${row.s("time")}").atZone(ZoneId.of(source.timeZone)).toInstant().toEpochMilli()
                val full = Text.plain(row.s("title"))
                val split = full.indexOf(": ")
                val league = if (split > 0) full.substring(0, split) else "Football"
                val title = if (split > 0) full.substring(split + 2) else full
                val players = row.a("embeds").mapNotNull { value ->
                    val player = value.obj()
                    runCatching { Player(Web.url(player.s("iframe")).toString(), player.s("name"), player.s("lang")) }.getOrNull()
                }
                val page = players.firstOrNull()?.let { Web.resolve(source.url, "/reproducir/?url=${Web.enc(it.url)}") } ?: source.url
                Listing(source, title, league, page, starts = kickoff, players = players)
            }.getOrNull()
        }
    }
}
