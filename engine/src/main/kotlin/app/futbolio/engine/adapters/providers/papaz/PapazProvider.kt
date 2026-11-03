package app.futbolio.engine.adapters.providers.papaz

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneOffset

internal object PapazProvider : Provider {
    override suspend fun resolve(item: Listing, net: Upstream) =
        StaticPlayerResolver(net, PapazPlayerExtractor::extract, PapazPlayerProtocol(net)::resolve).resolve(item)

    override suspend fun recover(source: SourceConfig, net: Upstream): ProviderResult? {
        // A listing script cannot establish the current player domain. Never build event
        // links under Bitly/Linktree when all published entry links are unavailable.
        if (source.dataUrl.isBlank() || Web.url(source.url).host in landingHosts) return null
        val doc = net.text(source.dataUrl, SourceHeaders(source.url))
        return parse(source, doc.text, source.url).takeIf { it.isNotEmpty() }?.let {
            ProviderResult(it, "Listing script reachable, but the homepage/player domain was not verified", source.url)
        }
    }

    override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
        val home = discoverLanding(source, net)
        require(Web.url(home.url).host !in landingHosts) { "Papaz entry page did not resolve to the provider website" }
        val portalRows = parsePortal(source, home.text, home.url)
        if (portalRows.isNotEmpty()) return ProviderResult(portalRows, activeUrl = home.url)
        val rows = if (source.dataUrl.isNotBlank()) parse(source, net.text(source.dataUrl, SourceHeaders(home.url)).text, home.url) else {
            val scripts = StringBuilder(home.text)
            for (url in Text.scripts(home.text, home.url).filter { Regex("script[\\w-]*\\.js(?:\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(4)) {
                try { scripts.append('\n').append(net.text(url, SourceHeaders(home.url)).text) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
            parse(source, scripts.toString(), home.url)
        }
        require(rows.isNotEmpty()) { "No Papaz data found; check the source and data URLs" }
        return ProviderResult(rows, activeUrl = home.url)
    }

    private val landingHosts = setOf("bit.ly", "www.bit.ly", "linktr.ee", "www.linktr.ee")

    fun parsePortal(source: SourceConfig, html: String, base: String): List<Listing> {
        val doc = Jsoup.parse(html, base)
        val out = mutableListOf<Listing>()
        for (node in doc.select("#mac-listesi .match-list li[data-source]").take(500)) {
            if (node.attr("data-category") != "futbol") continue
            runCatching {
                val name = Text.plain(node.attr("data-name"))
                val league = Text.plain(node.attr("data-search")).removePrefix(name).trim().replace(Regex("^20\\d{2}(?:20\\d{2})?\\s*"), "")
                val variants = codec.parseToJsonElement(node.attr("data-source")) as JsonArray
                val players = variants.mapNotNull { value ->
                    val player = value.obj()
                    val target = player.s("tip")
                    val link = player.s("link")
                    when (target) {
                        "vip", "viptv" -> link.takeIf { it.matches(Regex("[0-9]{1,20}")) }?.let { Player(base, player.s("ad"), "tr", target, it) }
                        "frame", "m3u8" -> runCatching { Player(Web.resolve(base, link), player.s("ad"), "tr", target) }.getOrNull()
                        else -> null
                    }
                }
                if (players.isNotEmpty()) out += Listing(source, name, league.ifBlank { "Football" }, Web.resolve(base, "/?futbolio_match=${hash(name)}"),
                    timeLabel = node.selectFirst(".saat")?.text().orEmpty(), players = players,
                    homeLogo = node.attr("data-home_logo"), awayLogo = node.attr("data-away_logo"))
            }
        }
        for (node in doc.select(".channel-item[data-source][data-target], #tv-listesi li[data-source][data-target]").take(100)) {
            val target = node.attr("data-target")
            val value = node.attr("data-source")
            val originalName = node.attr("data-name")
            val name = Text.plain(originalName)
            if (target !in listOf("vip", "viptv") || !value.matches(Regex("[0-9]{1,20}"))) continue
            runCatching { out += Listing(source, name, "Turkish sports channels", Web.resolve(base, "/?futbolio_channel=$value"), ListingKind.CHANNEL,
                players = listOf(Player(base, name, "tr", target, value)), identityTitle = originalName) }
        }
        return out.distinctBy { it.id }.take(600)
    }

    fun parse(source: SourceConfig, script: String, base: String): List<Listing> {
        val out = mutableListOf<Listing>()
        for (value in (Text.variable(script, "karsilasmalar") + Text.variable(script, "futbolMatches")).take(1000)) {
            val row = value.obj()
            if (row.s("title").isBlank() || row.s("url").isBlank() || Text.otherSport(row.s("league"))) continue
            runCatching {
                val date = row.s("tarih").ifBlank { row.s("_sort_key").take(10) }
                val start = runCatching { LocalDateTime.parse("${date}T${row.s("time")}:00").toInstant(ZoneOffset.ofHours(3)).toEpochMilli() }.getOrDefault(0)
                out += Listing(source, Text.plain(row.s("title")), Text.plain(row.s("league")).ifBlank { "Football" }, Web.resolve(base, row.s("url")), starts = start, timeLabel = row.s("time"))
            }
        }
        for (value in Text.variable(script, "channels").take(600)) {
            val row = value.obj()
            if (row.s("url").isBlank()) continue
            runCatching { out += Listing(source, Text.plain(row.s("title")), "Turkish sports channels", Web.resolve(base, row.s("url")), ListingKind.CHANNEL) }
        }
        return out.take(600)
    }
}
