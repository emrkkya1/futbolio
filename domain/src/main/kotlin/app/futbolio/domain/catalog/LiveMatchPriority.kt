package app.futbolio.domain.catalog

import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.domain.match.MatchState

/** Shared catalogue interest score, not stream quality. Prefetch selection uses visible Live Now entries only. */
object LiveMatchPriority {
    private val favouriteTeams = setOf("galatasaray", "fenerbahce", "besiktas")
    private val leagues = mapOf(
        "uefa.champions" to 900, "uefa.europa" to 800, "uefa.europa.conf" to 700,
        "tur.1" to 600, "tur.2" to 590, "tur.cup" to 580,
        "eng.1" to 500, "esp.1" to 400, "ger.1" to 300, "ita.1" to 200,
        "fra.1" to 100, "por.1" to 90, "ned.1" to 80,
    )

    fun score(entry: CatalogEntry, preferences: PresentationConfig): Int {
        val teams = listOfNotNull(entry.home?.name, entry.away?.name).ifEmpty {
            MatchNormalizer.teams(entry.title, preferences)?.let { listOf(it.first.name, it.second.name) }.orEmpty()
        }
        val favourite = teams.any { MatchNormalizer.teamKey(it, preferences) in favouriteTeams }
        return (if (favourite) 10_000 else 0) + (leagues[entry.league.id] ?: 1)
    }

    fun select(entries: List<CatalogEntry>, preferences: PresentationConfig, now: Long, count: Int = 4): List<CatalogEntry> {
        require(count > 0)
        return entries.filter { !it.channel && CatalogVisibility.visible(it, now) && it.state(now) in listOf(MatchState.LIVE, MatchState.HALFTIME) }
            .sortedWith(compareByDescending<CatalogEntry> { score(it, preferences) }
                .thenByDescending { it.starts }.thenBy { it.id })
            .take(count)
    }
}
