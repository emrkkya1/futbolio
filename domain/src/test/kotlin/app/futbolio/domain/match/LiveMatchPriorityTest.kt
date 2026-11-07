package app.futbolio.domain.match

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import java.time.Instant
import kotlin.test.*

class LiveMatchPriorityTest {
    private val now = Instant.parse("2026-11-07T16:30:00Z").toEpochMilli()
    private val config = PresentationConfig()
    private fun entry(id: String, league: String, home: String = "Home $id", start: Long = now - 60_000) = CatalogEntry(
        id, "$home vs Away $id", Leagues.find(league), start,
        listOf(Listing(SourceConfig(id, "fixture", id, "https://fixture.example/"), "$home vs Away $id", league, "https://fixture.example/$id", starts = start)),
        Team(home), Team("Away $id"))

    @Test fun favouritesThenEuropeanTournamentsThenTurkishAndEnglishLeagues() {
        val entries = listOf(entry("pl", "Premier League"), entry("tur", "Süper Lig"), entry("conf", "Conference League"),
            entry("europa", "Europa League"), entry("cl", "Champions League"),
            entry("gs", "Süper Lig", "Galatasaray SK"), entry("fb", "Süper Lig", "Fenerbahçe"), entry("bjk", "Süper Lig", "Beşiktaş JK"))
        val sorted = LiveMatchPriority.select(entries, config, now, 8)
        assertEquals(setOf("gs", "fb", "bjk"), sorted.take(3).map { it.id }.toSet())
        assertEquals(listOf("cl", "europa", "conf", "tur", "pl"), sorted.drop(3).map { it.id })
        assertEquals(4, LiveMatchPriority.select(entries, config, now).size)
    }

    @Test fun channelsFinishedUpcomingAmbiguousAndUncuratedRowsAreNotCandidates() {
        val live = entry("live", "Premier League")
        val channel = entry("channel", "Süper Lig", "Galatasaray").let { row -> row.copy(listings = row.listings.map { it.copy(kind = ListingKind.CHANNEL) }) }
        val finished = entry("finished", "Süper Lig", "Fenerbahçe", now - 3 * CatalogVisibility.HOUR)
        val upcoming = entry("future", "Süper Lig", "Beşiktaş", now + 60_000)
        val ambiguous = entry("ambiguous", "Champions League").copy(ambiguous = true)
        val hidden = entry("hidden", "Unapproved League", "Galatasaray")
        assertEquals(listOf(live), LiveMatchPriority.select(listOf(channel, finished, upcoming, ambiguous, hidden, live), config, now))
    }

    @Test fun youthNamesAreNotMistakenForFavouriteSeniorTeamsAndUserAliasesWork() {
        val youth = entry("youth", "Champions League", "Galatasaray U19")
        assertTrue(LiveMatchPriority.score(youth, config) < LiveMatchPriority.score(entry("gs", "Süper Lig", "Galatasaray"), config))
        assertEquals(10_600, LiveMatchPriority.score(entry("alias", "Süper Lig", "GS"), config.copy(teamAliases = mapOf("GS" to "Galatasaray"))))
    }

    @Test fun priorityIsComputedBeforeAnyCatalogPageLimitAndResultsAreStable() {
        val low = (1..120).map { entry("$it", "Premier League") }
        val favourite = entry("gs", "Süper Lig", "Galatasaray", now - 1)
        val candidates = low + favourite
        assertEquals("gs", LiveMatchPriority.select(candidates, config, now).first().id)
        assertEquals(LiveMatchPriority.select(candidates, config, now), LiveMatchPriority.select(candidates.reversed(), config, now))
    }
}
