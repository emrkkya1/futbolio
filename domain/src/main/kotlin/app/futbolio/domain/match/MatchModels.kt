package app.futbolio.domain.match

enum class MatchState(val label: String) {
    UNKNOWN("Status unknown"), SCHEDULED("Not Started"), LIVE("Live"), HALFTIME("Live · Halftime"),
    FINISHED("Ended"), POSTPONED("Postponed"), CANCELLED("Cancelled"),
}

data class Team(val name: String, val id: String = "", val logo: String = "", val aliases: List<String> = emptyList())

data class ScoreFixture(
    val id: String, val league: League, val home: Team, val away: Team, val starts: Long,
    val state: MatchState, val homeScore: String, val awayScore: String, val detail: String,
    val observedAt: Long,
    val minute: String = "",
) {
    fun fresh(now: Long) = now - observedAt < 5 * 60_000
    fun scoreText(now: Long): String = if (fresh(now) && state in setOf(MatchState.LIVE, MatchState.HALFTIME) &&
        homeScore.matches(Regex("\\d{1,2}")) && awayScore.matches(Regex("\\d{1,2}"))) "$homeScore - $awayScore" else ""
    fun clockText(now: Long): String = if (!fresh(now)) "" else when (state) {
        MatchState.HALFTIME -> "HT"
        MatchState.LIVE -> minute
        else -> ""
    }
}
