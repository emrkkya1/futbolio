package app.futbolio.domain.catalog

import app.futbolio.domain.match.MatchState
import app.futbolio.domain.text.TextPolicy
import app.futbolio.domain.settings.PresentationConfig

data class CatalogQuery(val category: String, val search: String = "", val skip: Int = 0, val genre: String = "")

/** Shared query policy; HTTP decoding and HTML cleanup happen before this boundary. */
object CatalogSelection {
    fun select(entries: List<CatalogEntry>, query: CatalogQuery, now: Long,
        preferences: PresentationConfig = PresentationConfig()): List<CatalogEntry> {
        val normalized = TextPolicy.normalize(query.search)
        return entries.asSequence().filter { entry ->
            query.category == "provider-tests" || CatalogVisibility.visible(entry, now)
        }.filter { entry ->
            when (query.category) {
                "channels" -> entry.channel
                "turkish" -> !entry.channel && entry.turkish
                "football", "all-matches" -> !entry.channel
                "live" -> !entry.channel && entry.state(now) in listOf(MatchState.LIVE, MatchState.HALFTIME)
                "upcoming" -> !entry.channel && entry.starts > now && entry.state(now) !in listOf(MatchState.CANCELLED, MatchState.FINISHED)
                "search" -> true
                else -> false
            }
        }.filter { entry ->
            (query.genre.isBlank() || entry.league.name == query.genre) &&
                (normalized.isBlank() || TextPolicy.normalize("${entry.title} ${entry.league.name} ${entry.sources.joinToString()}").contains(normalized))
        }.sortedWith(LiveMatchPriority.ordering(preferences, now))
            .drop(query.skip.coerceAtLeast(0)).take(100).toList()
    }
}
