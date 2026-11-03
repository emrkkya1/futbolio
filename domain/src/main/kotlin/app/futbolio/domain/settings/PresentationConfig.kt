package app.futbolio.domain.settings

import java.time.ZoneId

data class PresentationConfig(
    val displayTimeZone: String = "Europe/Istanbul",
    val hideFinished: Boolean = true,
    val showHalftime: Boolean = true,
    /** Legacy persisted preference only; no longer enables score enrichment. */
    val scoresEnabled: Boolean = false,
    val teamAliases: Map<String, String> = emptyMap(),
    val leagueAliases: Map<String, String> = emptyMap(),
) {
    fun validate() {
        ZoneId.of(displayTimeZone)
        require(teamAliases.size <= 1000 && leagueAliases.size <= 200)
        require((teamAliases.entries + leagueAliases.entries).all { it.key.length <= 100 && it.value.length <= 100 })
    }
}
