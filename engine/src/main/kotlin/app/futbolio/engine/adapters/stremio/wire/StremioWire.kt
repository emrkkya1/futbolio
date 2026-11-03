package app.futbolio.engine.adapters.stremio.wire

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.CatalogEntry
import app.futbolio.domain.match.*
import app.futbolio.domain.settings.DisplayTime
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.HttpUpstream
import app.futbolio.engine.Web
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class StremioWire(private val host: HostConfig) {
    fun manifest() = buildJsonObject {
        put("id", "community.futbolio.local")
        put("version", Product.VERSION); put("name", "Futbolio")
        put("description", "Live football discovery. Keep Futbolio running during playback.")
        put("types", JsonArray(listOf(JsonPrimitive("tv"))))
        put("resources", JsonArray(listOf("catalog", "meta", "stream").map(::JsonPrimitive)))
        put("idPrefixes", JsonArray(listOf(JsonPrimitive("fut:"))))
        putJsonArray("catalogs") {
            for ((id, name) in listOf("live" to "Live Now", "upcoming" to "Starting Soon", "turkish" to "Turkish Football", "channels" to "Sports Channels", "all-matches" to "All Matches", "search" to "Search Futbolio")) add(buildJsonObject {
                put("type", "tv"); put("id", id); put("name", name)
                putJsonArray("extra") {
                    if (id == "search") add(buildJsonObject { put("name", "search"); put("isRequired", true) })
                    else {
                        if (id != "channels") add(buildJsonObject { put("name", "skip"); put("isRequired", false) })
                        if (id in listOf("all-matches", "turkish")) add(buildJsonObject {
                            put("name", "genre"); put("isRequired", false)
                            val leagues = if (id == "turkish") Leagues.curated.filter { it.id.startsWith("tur.") }
                                else Leagues.curated
                            put("optionsLimit", 1)
                            put("options", JsonArray(leagues.map { JsonPrimitive(it.name) }))
                        })
                        if (id != "channels") add(buildJsonObject { put("name", "search"); put("isRequired", false) })
                    }
                }
            })
        }
        putJsonObject("behaviorHints") { put("configurable", false) }
    }

    private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun discoverLink(catalog: String, genre: String): String =
        "stremio:///discover/${encoded(host.manifest)}/tv/$catalog?genre=${encoded(genre)}"

    private fun searchLink(team: String) = "stremio:///search?search=${encoded(team)}"

    private fun metaLinks(row: CatalogEntry): JsonArray = buildJsonArray {
        if (!row.channel) {
            add(buildJsonObject {
                put("name", row.league.name)
                put("category", "Competition")
                put("url", discoverLink(if (row.league.id.startsWith("tur.")) "turkish" else "all-matches", row.league.name))
            })
            listOfNotNull(row.home?.name, row.away?.name).distinct().forEach { team ->
                add(buildJsonObject { put("name", team); put("category", "Team"); put("url", searchLink(team)) })
            }
        }
    }

    fun meta(row: CatalogEntry, preferences: PresentationConfig, now: Long) = buildJsonObject {
        val kickoff = DisplayTime.kickoff(row.starts, preferences)
        val state = row.state(now).label
        val home = row.home; val away = row.away
        val title = if (!row.channel && home != null && away != null) "${home.name} vs ${away.name}" else row.title
        put("id", row.id); put("type", "tv"); put("name", title); put("posterShape", "square")
        put("poster", "${host.base}/poster/${Web.enc(row.id)}.jpg?v=6")
        val verified = row.fixture?.let { it.fresh(now) || it.state in listOf(MatchState.FINISHED, MatchState.CANCELLED) } == true
        val date = if (row.starts > 0) Instant.ofEpochMilli(row.starts).atZone(ZoneId.of(preferences.displayTimeZone)) else null
        val fullKickoff = date?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm", Locale.UK))
            ?.let { "$it (${preferences.displayTimeZone}, UTC${date.offset.id.takeUnless { offset -> offset == "Z" } ?: "+00:00"})" }
            ?: "To be confirmed"
        val providers = "${row.sources.size} ${if (row.sources.size == 1) "provider" else "providers"} · ${row.sources.joinToString(", ")}"
        put("description", if (row.channel) "$title\n24/7 sports channel\nAvailable from $providers\nSelect a source to watch. Website options open in your browser."
            else "${row.league.name}\nKickoff: $fullKickoff\nStatus: $state${if (verified) " · verified fixture data" else " · kickoff-based estimate"}\nAvailable from $providers${if (row.needsFixtureLookup) "\nProvider schedules differ; kickoff verification is pending." else ""}\nSelect a source to watch. Website options open in your browser.")
        put("genres", JsonArray(listOf(JsonPrimitive(row.league.name))))
        put("links", metaLinks(row))
        put("releaseInfo", if (row.channel) "24/7 channel" else "$state · $kickoff")
        putJsonObject("behaviorHints") { put("defaultVideoId", row.id) }
    }

    fun stream(choice: StreamChoice, localHls: (StreamChoice) -> String) = buildJsonObject {
        put("name", "${choice.providers.joinToString(" / ")} · ${if (choice.externalUrl.isNotEmpty()) "Website" else "Live"}")
        put("title", choice.title)
        if (choice.externalUrl.isNotEmpty()) put("externalUrl", choice.externalUrl) else {
            val hls = isHls(choice)
            put("url", if (hls) localHls(choice) else choice.url)
            putJsonObject("behaviorHints") {
                put("notWebReady", !hls)
                if (!hls) putJsonObject("proxyHeaders") { putJsonObject("request") {
                    put("User-Agent", HttpUpstream.USER_AGENT); put("Referer", choice.headers.referer); put("Origin", choice.headers.origin)
                    choice.headers.extra.forEach { (key, value) -> put(key, value) }
                } }
            }
        }
    }
}
