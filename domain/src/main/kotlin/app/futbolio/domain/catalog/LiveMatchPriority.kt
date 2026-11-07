package app.futbolio.domain.catalog

import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.domain.match.MatchState
import kotlin.math.abs

data class MatchPriorityScore(
    val favourites: Int = 0,
    val competition: Int = 0,
    val teams: Int = 0,
    val matchup: Int = 0,
    val activity: Int = 0,
    val urgency: Int = 0,
) {
    val total get() = favourites + competition + teams + matchup + activity + urgency
}

/** Local editorial interest, not team strength, betting odds or stream quality. No I/O. */
object LiveMatchPriority {
    private val favouriteTeams = setOf("galatasaray", "fenerbahce", "besiktas")
    private val leagues = mapOf(
        "uefa.champions" to 2400, "uefa.europa" to 2000, "uefa.europa.conf" to 1700,
        "tur.1" to 1500, "tur.2" to 1350, "tur.cup" to 1300,
        "eng.1" to 1200, "esp.1" to 1100, "ger.1" to 1000, "ita.1" to 950,
        "fra.1" to 850, "por.1" to 750, "ned.1" to 700,
    )
    private val rankingAliases = mapOf("ac milan" to "milan", "bayern" to "bayern munich",
        "bayer 04 leverkusen" to "bayer leverkusen", "paris sg" to "paris saint germain")
    private fun key(name: String, preferences: PresentationConfig): String =
        MatchNormalizer.teamKey(name, preferences).let { rankingAliases[it] ?: it }
    private val defaults = PresentationConfig()
    private val prominence = buildMap {
        listOf("Galatasaray", "Fenerbahçe", "Beşiktaş", "Real Madrid", "Barcelona", "Manchester United",
            "Manchester City", "Liverpool", "Arsenal", "Chelsea", "Bayern Munich", "Borussia Dortmund",
            "Paris Saint-Germain", "Juventus", "Inter Milan", "Milan", "Atlético Madrid")
            .forEach { put(key(it, defaults), 250) }
        listOf("Tottenham Hotspur", "Newcastle United", "Aston Villa", "Bayer Leverkusen", "RB Leipzig", "VfB Stuttgart",
            "Napoli", "Roma", "Lazio", "Atalanta", "Sevilla", "Valencia", "Athletic Club", "Real Sociedad",
            "Marseille", "Lyon", "Monaco", "FC Porto", "Benfica", "Sporting CP", "Ajax", "PSV Eindhoven",
            "Feyenoord", "Trabzonspor").forEach { put(key(it, defaults), 140) }
    }
    private val derbies = listOf("Galatasaray" to "Fenerbahçe", "Galatasaray" to "Beşiktaş", "Fenerbahçe" to "Beşiktaş",
        "Real Madrid" to "Barcelona", "Real Madrid" to "Atlético Madrid", "Manchester United" to "Manchester City",
        "Manchester United" to "Liverpool", "Arsenal" to "Tottenham Hotspur", "Liverpool" to "Everton",
        "Inter Milan" to "Milan", "Roma" to "Lazio", "Bayern Munich" to "Borussia Dortmund",
        "Paris Saint-Germain" to "Marseille", "Benfica" to "FC Porto", "Benfica" to "Sporting CP",
        "Ajax" to "Feyenoord").map { setOf(key(it.first, defaults), key(it.second, defaults)) }.toSet()
    private val minute = Regex("^(\\d{1,3})(?:\\+(\\d{1,2}))?['’]?$")

    fun score(entry: CatalogEntry, preferences: PresentationConfig, now: Long? = null): Int = breakdown(entry, preferences, now).total

    fun breakdown(entry: CatalogEntry, preferences: PresentationConfig, now: Long? = null): MatchPriorityScore =
        calculate(entry, preferences, now) { key(it, preferences) }

    private fun calculate(entry: CatalogEntry, preferences: PresentationConfig, now: Long?, teamKey: (String) -> String): MatchPriorityScore {
        if (entry.channel) return MatchPriorityScore()
        val names = listOfNotNull(entry.home?.name, entry.away?.name).ifEmpty {
            MatchNormalizer.teams(entry.title, preferences)?.let { listOf(it.first.name, it.second.name) }.orEmpty()
        }
        val teams = names.map(teamKey).distinct().take(2)
        val favourites = teams.count { it in favouriteTeams }
        val prominenceValues = teams.map { prominence[it] ?: 0 }
        val activity = if (now == null) 0 else when (entry.state(now)) {
            MatchState.LIVE -> 180
            MatchState.HALFTIME -> 160
            MatchState.SCHEDULED -> when (entry.starts - now) {
                in 0L..900_000L -> 120
                in 900_001L..3_600_000L -> 80
                in 3_600_001L..14_400_000L -> 30
                else -> 0
            }
            else -> 0
        }
        var urgency = 0
        val fixture = entry.fixture
        if (now != null && fixture != null && fixture.observedAt <= now && fixture.fresh(now) && fixture.state == MatchState.LIVE) {
            val clock = minute.matchEntire(fixture.minute.trim())
            val elapsed = clock?.let { it.groupValues[1].toInt() + (it.groupValues[2].toIntOrNull() ?: 0) }
            val home = fixture.homeScore.takeIf { it.matches(Regex("\\d{1,2}")) }?.toInt()
            val away = fixture.awayScore.takeIf { it.matches(Regex("\\d{1,2}")) }?.toInt()
            if (elapsed != null && elapsed in 60..150 && home != null && away != null && abs(home - away) <= 1)
                urgency = (if (home == away) 40 else 20) + (if (elapsed >= 75) 50 else 25)
        }
        return MatchPriorityScore(
            favourites = (if (favourites > 0) 10_000 else 0) + (if (favourites == 2) 1000 else 0),
            competition = leagues[entry.league.id] ?: 100,
            teams = prominenceValues.sum(),
            matchup = (if (prominenceValues.size == 2 && prominenceValues.all { it >= 200 }) 350 else 0) + (if (teams.toSet() in derbies) 250 else 0),
            activity = activity,
            urgency = urgency,
        )
    }

    /** Memoize scores/team normalization once per sort, not for every comparison. */
    fun ordering(preferences: PresentationConfig, now: Long): Comparator<CatalogEntry> {
        val scores = mutableMapOf<String, Int>()
        val teams = mutableMapOf<String, String>()
        fun value(entry: CatalogEntry) = scores.getOrPut(entry.id) {
            calculate(entry, preferences, now) { name -> teams.getOrPut(name) { key(name, preferences) } }.total
        }
        return compareByDescending<CatalogEntry> { value(it) }
            .thenByDescending { it.state(now) in setOf(MatchState.LIVE, MatchState.HALFTIME) }
            .thenBy { it.starts }.thenBy { it.title }.thenBy { it.id }
    }

    fun select(entries: List<CatalogEntry>, preferences: PresentationConfig, now: Long, count: Int = 4): List<CatalogEntry> {
        require(count > 0)
        return entries.filter { !it.channel && CatalogVisibility.visible(it, now) && it.state(now) in listOf(MatchState.LIVE, MatchState.HALFTIME) }
            .sortedWith(ordering(preferences, now))
            .take(count)
    }
}
