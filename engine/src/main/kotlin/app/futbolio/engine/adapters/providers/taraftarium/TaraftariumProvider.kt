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
            for (value in rows.take(600)) {
                val row = value.obj()
                if (row.s("type") != "football") continue
                runCatching {
                    val id = Web.query(Web.resolve(api, row.s("URL")), "id")
                    if (id.isBlank()) return@runCatching
                    out += Listing(source, "${Text.plain(row.s("HomeTeam"))} - ${Text.plain(row.s("AwayTeam"))}", Text.plain(row.s("league")).ifBlank { "Football" },
                        Web.resolve(home.url, "/channel.html?id=${Web.enc(id)}"), timeLabel = row.s("Time"), identityTitle = "${row.s("HomeTeam")} - ${row.s("AwayTeam")}")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { warning = "Match listing unavailable; channels remain available" }
        require(out.isNotEmpty()) { "No Taraftarium channels found; check the current domain" }
        return ProviderResult(out.take(600), warning, home.url)
    }
}
