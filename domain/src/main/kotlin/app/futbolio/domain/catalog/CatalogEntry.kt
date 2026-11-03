package app.futbolio.domain.catalog

import app.futbolio.domain.match.*

data class CatalogEntry(
    val id: String, val title: String, val league: League, val starts: Long, val listings: List<Listing>,
    val home: Team? = null, val away: Team? = null, val fixture: ScoreFixture? = null,
    val needsFixtureLookup: Boolean = false, val ambiguous: Boolean = false,
) {
    val channel = listings.all { it.channel }
    val sources = listings.map { it.source.name }.distinct()
    val turkish = league.id.startsWith("tur.") || listings.any { it.turkish }
    fun state(now: Long): MatchState {
        if (channel || starts <= 0) return MatchState.UNKNOWN
        // A final result must never turn back into an inferred live match as the cache ages.
        fixture?.takeIf { it.state in setOf(MatchState.FINISHED, MatchState.CANCELLED, MatchState.POSTPONED) || it.fresh(now) }
            ?.state?.takeUnless { it == MatchState.UNKNOWN }?.let { return it }
        return when {
            now < starts -> MatchState.SCHEDULED
            now >= starts + CatalogVisibility.duration(this) -> MatchState.FINISHED
            else -> MatchState.LIVE
        }
    }
}
