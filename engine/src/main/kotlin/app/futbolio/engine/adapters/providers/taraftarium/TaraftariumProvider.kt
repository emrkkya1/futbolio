package app.futbolio.engine.adapters.providers.taraftarium

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Base64

internal object TaraftariumProvider : Provider {
    override suspend fun resolve(item: Listing, net: Upstream) = StaticPlayerResolver(net, TaraftariumPlayerExtractor::extract).resolve(item)

    override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
        val home = discoverLanding(source, net)
        val out = mutableListOf<Listing>()
        var warning = ""
        for ((url, label) in Text.anchors(home.text, home.url).filter { Regex("/channel\\.html\\?id=[^&]+").containsMatchIn(it.first) }.take(300)) {
            runCatching { out += Listing(source, label.replace(Regex("7/24\\s*$"), "").trim(), "Turkish sports channels", url, ListingKind.CHANNEL) }
        }
        val encoded = Text.match(home.text, "(?:const|let|var)\\s+apiBase\\s*=\\s*atob\\(['\"]([A-Za-z0-9+/=]+)['\"]\\)")
        val api = source.apiUrl.ifBlank { runCatching { Web.resolve(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8), "/matches.php") }.getOrDefault("") }
        if (api.isNotBlank()) try {
            val rows = codec.parseToJsonElement(net.text(api, SourceHeaders(home.url)).text) as? JsonArray ?: error("Invalid matches")
            val matches = rows.take(600).map { it.obj() }.filter { it.s("type") == "football" }
            fun matchKey(row: JsonObject) = Triple(row.s("HomeTeam"), row.s("AwayTeam"), row.s("Time"))
            fun competition(row: JsonObject) = Text.plain(row.s("league")).substringBefore(" | ").trim()
            fun restriction(league: String) = Text.norm(league).startsWith("sadece turkiye")
            val competitions = matches.filterNot { restriction(competition(it)) }.groupBy(::matchKey)
                .mapValues { (_, variants) -> variants.map(::competition).filter(String::isNotBlank).distinct().singleOrNull() }
            for (row in matches) {
                runCatching {
                    val id = Web.query(Web.resolve(api, row.s("URL")), "id")
                    if (id.isBlank()) return@runCatching
                    // Some alternate rows put a region/quality note in the league field.
                    // It is not a conflicting competition and must not hide the whole match.
                    val league = competition(row).let { if (restriction(it)) competitions[matchKey(row)].orEmpty() else it }
                    out += Listing(source, "${Text.plain(row.s("HomeTeam"))} - ${Text.plain(row.s("AwayTeam"))}", league.ifBlank { "Football" },
                        Web.resolve(home.url, "/channel.html?id=${Web.enc(id)}"), timeLabel = row.s("Time"), identityTitle = "${row.s("HomeTeam")} - ${row.s("AwayTeam")}")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { warning = "Match listing unavailable; channels remain available" }
        require(out.isNotEmpty()) { "No Taraftarium channels found; check the current domain" }
        return ProviderResult(out.take(600), warning, home.url)
    }
}
