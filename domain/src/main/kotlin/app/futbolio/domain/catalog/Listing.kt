package app.futbolio.domain.catalog

import app.futbolio.domain.identity.StableIdentity
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.provider.SourceUrl
import app.futbolio.domain.text.TextPolicy

/** Opaque player hints are interpreted only by the originating provider adapter. */
data class Player(val url: String, val label: String = "", val language: String = "", val target: String = "", val source: String = "") {
    override fun toString() = "Player(location=[redacted])"
}

enum class ListingKind { MATCH, CHANNEL }

/** Adapter-supplied, plain-text listing; identity remains separate from an aggregated event. */
data class Listing(
    val source: SourceConfig,
    val title: String,
    val league: String,
    val page: String,
    val kind: ListingKind = ListingKind.MATCH,
    val starts: Long = 0,
    val ends: Long = 0,
    val timeLabel: String = "",
    val poster: String = "",
    val players: List<Player> = emptyList(),
    val homeLogo: String = "",
    val awayLogo: String = "",
    /** Only for adapters whose historic identity used a raw title before display cleanup. */
    val identityTitle: String? = null,
    /** Discovery date anchor for clock-only provider schedules; deliberately excluded from identity. */
    val observedAt: Long = 0,
) {
    init {
        SourceUrl.parse(page)
        require(title.isNotBlank()) { "Empty listing title" }
    }

    val id = "fut:${source.id}:${StableIdentity.hash("${source.id}|$page|${identityTitle ?: title}|$starts")}"
    val channel get() = kind == ListingKind.CHANNEL
    val turkish get() = TextPolicy.isTurkish("$title $league")
    fun expired(now: Long) = !channel && if (ends > 0) ends < now else starts > 0 && starts + 3 * 3_600_000 < now
    override fun toString() = "Listing(id=$id, location=[redacted])"
}
