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

    @Test fun matchCataloguesSortByPriorityBeforeKickoffAndPagination() {
        for (category in listOf("live", "football", "all-matches", "search", "upcoming")) {
            val start = if (category == "upcoming") now + 60_000 else now - 60_000
            val low = (1..120).map { entry("pl-$it", "Premier League", start = start) }
            val gs = entry("gs", "Süper Lig", "Galatasaray", start + 1000)
            val cl = entry("cl", "Champions League", start = start + 500)
            val entries = low + cl + gs
            val sorted = CatalogSelection.select(entries, CatalogQuery(category), now, config)
            assertEquals(listOf("gs", "cl"), sorted.take(2).map { it.id }, category)
            assertEquals(100, sorted.size)
            assertEquals("cl", CatalogSelection.select(entries, CatalogQuery(category, skip = 1), now, config).first().id)
            assertEquals(sorted, CatalogSelection.select(entries.reversed(), CatalogQuery(category), now, config))
        }
    }

    @Test fun turkishCatalogueAndFilteredSearchRespectPriorityAndConfiguredAliases() {
        val entries = listOf(entry("other", "Süper Lig", "Trabzonspor"), entry("gs", "Süper Lig", "GS"))
        val preferences = config.copy(teamAliases = mapOf("GS" to "Galatasaray"))
        for (query in listOf(CatalogQuery("turkish"), CatalogQuery("search", search = "vs", genre = "Süper Lig"))) {
            assertEquals(listOf("gs", "other"), CatalogSelection.select(entries, query, now, preferences).map { it.id })
        }
    }

    @Test fun equalPriorityUsesLiveStatusThenKickoffAndChannelsKeepTheirOrdering() {
        val entries = listOf(entry("later", "Premier League", start = now + 120_000),
            entry("earlier", "Premier League", start = now + 60_000), entry("live", "Premier League"))
        assertEquals(listOf("live", "earlier", "later"),
            CatalogSelection.select(entries, CatalogQuery("football"), now, config).map { it.id })
        val channels = listOf(entry("z", "Süper Lig", "Galatasaray"), entry("a", "Premier League", "Arsenal"))
            .map { row -> row.copy(listings = row.listings.map { it.copy(kind = ListingKind.CHANNEL) }) }
        assertEquals(listOf("a", "z"), CatalogSelection.select(channels, CatalogQuery("channels"), now, config).map { it.id })
    }
}
