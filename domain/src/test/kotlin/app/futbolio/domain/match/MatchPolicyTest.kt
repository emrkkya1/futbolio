package app.futbolio.domain.match

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import java.time.Instant
import kotlin.test.*

class MatchPolicyTest {
    private val now = Instant.parse("2026-11-01T19:00:00Z").toEpochMilli()
    private val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
    private val options = PresentationConfig()
    private fun row(id: String = "fixture", title: String = "Manchester Utd - Chelsea", start: Long = now) =
        Listing(source.copy(id = id), title, "Premier League", "https://fixture.example/game", starts = start)
    private fun fixture(state: MatchState, observed: Long = now) = ScoreFixture("123", Leagues.find("Premier League"),
        Team("Manchester United", "1", aliases = listOf("Man Utd")), Team("Chelsea", "2"), now, state, "1", "0", "HT", observed)

    @Test fun aliasesMergeSameFixtureAcrossProviders() {
        val entries = MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea"), row("other", "Manchester United vs Chelsea")), emptyList(), options, now)
        assertEquals(1, entries.size)
        assertEquals(2, entries.single().listings.size)
        assertEquals("Manchester United vs Chelsea", entries.single().title)
    }

    @Test fun reversedYouthAndWomensFixturesStaySeparate() {
        val rows = listOf(row(title = "Arsenal - Chelsea"), row("youth", "Arsenal U19 - Chelsea U19"),
            row("reverse", "Chelsea - Arsenal"), row("women", "Arsenal Women - Chelsea Women"))
        assertEquals(4, MatchNormalizer.aggregate(rows, emptyList(), options, now).size)
    }

    @Test fun differentDaysAndUnknownKickoffsStaySeparate() {
        assertEquals(2, MatchNormalizer.aggregate(listOf(row(), row("other", start = now + 24 * 3_600_000)), emptyList(), options, now).size)
        assertEquals(2, MatchNormalizer.aggregate(listOf(row(start = 0), row("other", start = 0)), emptyList(), options, now).size)
    }

    @Test fun awayAtHomeTitlesAreOrderedCorrectly() {
        val teams = MatchNormalizer.teams("Chelsea at Arsenal", options)!!
        assertEquals("Arsenal", teams.first.name)
        assertEquals("Chelsea", teams.second.name)
    }

    @Test fun statusFilteringRequiresFreshUnambiguousEvidence() {
        val rows = listOf(row(title = "Man Utd - Chelsea"))
        assertEquals(MatchState.HALFTIME, MatchNormalizer.aggregate(rows, listOf(fixture(MatchState.HALFTIME)), options, now).single().state(now))
        assertTrue(MatchNormalizer.aggregate(rows, listOf(fixture(MatchState.FINISHED)), options, now).isEmpty())
        assertTrue(MatchNormalizer.aggregate(rows, listOf(fixture(MatchState.HALFTIME)), options.copy(showHalftime = false), now).isEmpty())
        assertTrue(MatchNormalizer.aggregate(rows, listOf(fixture(MatchState.FINISHED, now - 600_000)), options, now).isEmpty())
        assertNull(MatchNormalizer.aggregate(rows, listOf(fixture(MatchState.LIVE), fixture(MatchState.LIVE).copy(id = "456")), options, now).single().fixture)
    }

    @Test fun timeOnlyKickoffRespectsSourceZoneAndSeasonalOffset() {
        val istanbul = row(start = 0).copy(timeLabel = "22:00")
        assertEquals(now, MatchNormalizer.kickoff(istanbul, now))
        val london = row(start = 0).copy(source = source.copy(timeZone = "Europe/London"), timeLabel = "12:00")
        val summer = Instant.parse("2030-06-01T11:00:00Z").toEpochMilli()
        val winter = Instant.parse("2030-01-01T12:00:00Z").toEpochMilli()
        assertEquals(summer, MatchNormalizer.kickoff(london, summer))
        assertEquals(winter, MatchNormalizer.kickoff(london, winter))
    }

    @Test fun selectionSharesProtocolFilteringPaginationAndGenrePolicy() {
        val rows = (1..201).map { row("source-$it", "Home $it vs Away $it", now + 60_000) }
        val entries = MatchNormalizer.aggregate(rows, emptyList(), options, now)
        assertEquals(100, CatalogSelection.select(entries, CatalogQuery("football"), now).size)
        assertEquals(1, CatalogSelection.select(entries, CatalogQuery("football", skip = 200), now).size)
        assertTrue(CatalogSelection.select(entries, CatalogQuery("unknown"), now).isEmpty())
        assertTrue(CatalogSelection.select(entries, CatalogQuery("live"), now).isEmpty())
        assertEquals(100, CatalogSelection.select(entries, CatalogQuery("upcoming", genre = "Premier League"), now).size)
        assertTrue(CatalogSelection.select(entries, CatalogQuery("football", genre = "LaLiga"), now).isEmpty())
    }

    @Test fun publicIdsRemainCompatibleWithThePreviousEngine() {
        val listing = Listing(source.copy(kind = "ppv"), "Home vs Away", "Premier League", "https://fixture.example/game", starts = 1893456000000)
        assertEquals("fut:fixture:254e293007bae4e03175e2ff", listing.id)
        assertEquals("fut:event:5c4e335ca697d1f2205e700f", MatchNormalizer.aggregate(listOf(listing), emptyList(), options, 1893456000000).single().id)
    }
}
