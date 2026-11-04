package app.futbolio.domain.catalog

import app.futbolio.domain.match.MatchState
import app.futbolio.domain.match.ScoreFixture
import app.futbolio.domain.match.Team
import app.futbolio.domain.settings.PresentationConfig

/** Optional display/status overlay: never changes listing identity, teams, league or kickoff. */
object ScoreEnrichment {
    fun apply(entries: List<CatalogEntry>, fixtures: List<ScoreFixture>, config: PresentationConfig, now: Long): List<CatalogEntry> {
        if (fixtures.isEmpty()) return entries
        val squad = Regex("\\b(?:u\\d{1,2}|women|womens|w|reserves|ii|b)\\b")
        fun keys(team: Team): Set<String> {
            val primary = MatchNormalizer.teamKey(team.name, config)
            val qualifiers = squad.findAll(primary).map { it.value }.toSet()
            return (listOf(team.name) + team.aliases).filter(String::isNotBlank)
                .map { MatchNormalizer.teamKey(it, config) }
                .filter { key -> squad.findAll(key).map { it.value }.toSet() == qualifiers }.toSet()
        }
        data class Candidate(val fixture: ScoreFixture, val home: Set<String>, val away: Set<String>)
        val candidates = fixtures.filter { it.fresh(now) || it.state == MatchState.FINISHED }
            .distinctBy { it.id }.map { Candidate(it, keys(it.home), keys(it.away)) }
        return entries.map { entry ->
            if (entry.channel || entry.home == null || entry.away == null || entry.starts <= 0) return@map entry
            val home = MatchNormalizer.teamKey(entry.home.name, config)
            val away = MatchNormalizer.teamKey(entry.away.name, config)
            val matches = candidates.filter { candidate ->
                kotlin.math.abs(candidate.fixture.starts - entry.starts) <= 12 * CatalogVisibility.HOUR &&
                    (home in candidate.home && away in candidate.away || home in candidate.away && away in candidate.home)
            }
            val match = matches.singleOrNull() ?: return@map entry
            val fixture = match.fixture
            val reverse = home in match.away && away in match.home
            entry.copy(fixture = fixture.copy(home = entry.home, away = entry.away,
                homeScore = if (reverse) fixture.awayScore else fixture.homeScore,
                awayScore = if (reverse) fixture.homeScore else fixture.awayScore))
        }
    }
}
