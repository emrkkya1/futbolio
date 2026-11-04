package app.futbolio.engine.adapters.scores.espn

import app.futbolio.application.ports.ScoreFeed
import app.futbolio.application.ports.Upstream
import app.futbolio.domain.match.*
import app.futbolio.engine.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate

/** Public, unofficial scoreboard adapter. Availability is never required for discovery. */
internal class EspnScoreFeed(private val net: Upstream) : ScoreFeed {
    override suspend fun fixtures(league: League, date: LocalDate, observedAt: Long): List<ScoreFixture> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/${league.id}/scoreboard?dates=${date.toString().replace("-", "")}&limit=100"
        return parse(net.text(url).text, league, observedAt)
    }

    override suspend fun scoreboard(observedAt: Long): List<ScoreFixture> =
        parse(net.text("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?limit=150").text,
            League("all", "Football", emptyList()), observedAt)

    companion object {
        fun parse(text: String, league: League, now: Long): List<ScoreFixture> = codec.parseToJsonElement(text).obj().a("events").take(150).mapNotNull { value ->
            runCatching {
                val event = value.obj()
                val competition = event.a("competitions").first().obj()
                val teams = competition.a("competitors").map { it.obj() }
                val home = teams.single { it.s("homeAway") == "home" }
                val away = teams.single { it.s("homeAway") == "away" }
                fun team(row: JsonObject): Team {
                    val value = row["team"]!!.obj()
                    return Team(Text.plain(value.s("displayName")), value.s("id"), value.s("logo"),
                        listOf(value.s("shortDisplayName"), value.s("name"), value.s("abbreviation")).filter(String::isNotBlank).map(Text::plain))
                }
                val status = (event["status"] ?: competition["status"])!!.obj()
                val type = status["type"]!!.obj()
                val name = type.s("name")
                val state = when {
                    name.contains("POSTPON", true) -> MatchState.POSTPONED
                    name.contains("CANCEL", true) || name.contains("ABANDON", true) -> MatchState.CANCELLED
                    name.contains("HALFTIME", true) || name.contains("HALF_TIME", true) -> MatchState.HALFTIME
                    type.b("completed") || type.s("state") == "post" -> MatchState.FINISHED
                    type.s("state") == "pre" -> MatchState.SCHEDULED
                    type.s("state") == "in" -> MatchState.LIVE
                    else -> MatchState.UNKNOWN
                }
                ScoreFixture(event.s("id"), league, team(home), team(away),
                    Instant.parse(event.s("date").replace(Regex("(T\\d{2}:\\d{2})Z$"), "$1:00Z")).toEpochMilli(),
                    state, home.s("score"), away.s("score"), Text.plain(type.s("shortDetail")), now,
                    minute(status.s("displayClock").ifBlank { type.s("shortDetail") }))
            }.getOrNull()
        }

        private fun minute(value: String): String {
            val clean = value.trim().replace(Regex("['’′]"), "")
            return if (clean.matches(Regex("\\d{1,3}(?:\\+\\d{1,2})?"))) "$clean'" else ""
        }
    }
}
