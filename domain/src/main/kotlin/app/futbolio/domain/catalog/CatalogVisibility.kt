package app.futbolio.domain.catalog

import app.futbolio.domain.match.Leagues
import app.futbolio.domain.match.MatchState

/** Cheap, request-time policy: cached entries must still cross time boundaries immediately. */
object CatalogVisibility {
    const val HOUR = 3_600_000L
    const val UPCOMING = 24 * HOUR
    fun duration(entry: CatalogEntry) = if (entry.league.tournament) 3 * HOUR else 2 * HOUR

    fun visible(entry: CatalogEntry, now: Long): Boolean {
        if (entry.channel) return true
        if (!Leagues.allowed(entry.league) || entry.ambiguous || entry.starts <= 0) return false
        if (entry.starts > now + UPCOMING) return false
        val verifiedLive = entry.fixture?.let { it.fresh(now) && it.state in setOf(MatchState.LIVE, MatchState.HALFTIME) } == true
        if (!verifiedLive && now >= entry.starts + duration(entry)) return false
        return entry.state(now) !in setOf(MatchState.FINISHED, MatchState.CANCELLED, MatchState.POSTPONED)
    }
}
