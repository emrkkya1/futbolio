package app.futbolio.domain.match

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlin.test.*

class ScoreEnrichmentTest {
    private val now = 1_793_721_600_000L
    private val options = PresentationConfig()
    private val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
    private fun entry(title: String = "Man Utd vs Chelsea", starts: Long = now) = MatchNormalizer.aggregate(
        listOf(Listing(source, title, "Premier League", "${source.url}match", starts = starts)), emptyList(), options.copy(hideFinished = false), now).single()
    private fun fixture(state: MatchState = MatchState.LIVE, home: Team = Team("Manchester United"), away: Team = Team("Chelsea")) =
        ScoreFixture("espn", Leagues.find("Premier League"), home, away, now, state, "2", "1", "47'", now, "47'")

    @Test fun enrichmentPreservesIdentityTeamsKickoffAndProviderSources() {
        val original = entry()
        val enriched = ScoreEnrichment.apply(listOf(original), listOf(fixture()), options, now).single()
        assertEquals(original, enriched.copy(fixture = null))
        assertEquals("2 - 1", enriched.fixture!!.scoreText(now))
        assertEquals("47'", enriched.fixture!!.clockText(now))
    }

    @Test fun reversedProviderOrderReversesScoresWithoutRenamingTeams() {
        val original = entry("Chelsea vs Man Utd")
        val enriched = ScoreEnrichment.apply(listOf(original), listOf(fixture()), options, now).single()
        assertEquals(original.home, enriched.home)
        assertEquals("1 - 2", enriched.fixture!!.scoreText(now))
        assertEquals("Chelsea", enriched.fixture!!.home.name)
    }

    @Test fun missingAmbiguousStaleAndDifferentDayFixturesLeaveListingsUntouched() {
        val original = entry()
        val matching = fixture()
        for (fixtures in listOf(emptyList(), listOf(fixture(away = Team("Arsenal"))),
            listOf(matching, matching.copy(id = "second")), listOf(matching.copy(observedAt = now - 300_000)),
            listOf(matching.copy(starts = now - 24 * CatalogVisibility.HOUR)))) {
            assertSame(original, ScoreEnrichment.apply(listOf(original), fixtures, options, now).single())
        }
    }

    @Test fun seniorTeamsNeverMatchWomensOrYouthFixtures() {
        for (team in listOf("Manchester United U19", "Manchester United Women", "Manchester United B")) {
            val original = entry()
            assertSame(original, ScoreEnrichment.apply(listOf(original),
                listOf(fixture(home = Team(team, aliases = listOf("Manchester United", "Man Utd")))), options, now).single())
        }
    }

    @Test fun explicitTeamAliasesAreRespected() {
        val original = entry("United Fixture vs Chelsea")
        val custom = options.copy(teamAliases = mapOf("United Fixture" to "Manchester United"))
        assertNotNull(ScoreEnrichment.apply(listOf(original), listOf(fixture()), custom, now).single().fixture)
    }

    @Test fun halftimeIsVisibleAndFinishedMatchDisappearsAcrossAllCatalogs() {
        val original = entry()
        val halftime = ScoreEnrichment.apply(listOf(original), listOf(fixture(MatchState.HALFTIME)), options, now).single()
        assertTrue(CatalogVisibility.visible(halftime, now))
        assertEquals("HT", halftime.fixture!!.clockText(now))
        val finished = ScoreEnrichment.apply(listOf(original), listOf(fixture(MatchState.FINISHED)), options, now)
        for (category in listOf("football", "all-matches", "live", "upcoming", "turkish", "search")) {
            assertTrue(CatalogSelection.select(finished, CatalogQuery(category), now).isEmpty(), category)
        }
    }

    @Test fun scheduledMissingAndInvalidScoresNeverInventLiveScoreOrClock() {
        assertEquals("", fixture(MatchState.SCHEDULED).scoreText(now))
        assertEquals("", fixture(MatchState.SCHEDULED).clockText(now))
        assertEquals("", fixture().copy(homeScore = "").scoreText(now))
        assertEquals("", fixture().copy(awayScore = "-1").scoreText(now))
        assertEquals("", fixture().copy(observedAt = now - 300_000).clockText(now))
        assertEquals("0 - 0", fixture().copy(homeScore = "0", awayScore = "0").scoreText(now))
    }

    @Test fun verifiedLiveStatusOutlivesEstimatedExpiryUntilEndedOrStale() {
        val original = entry(starts = now - 2 * CatalogVisibility.HOUR)
        assertFalse(CatalogVisibility.visible(original, now))
        val live = ScoreEnrichment.apply(listOf(original), listOf(fixture().copy(starts = original.starts)), options, now).single()
        assertTrue(CatalogVisibility.visible(live, now), "An ESPN-confirmed live match must not expire at the old estimate")
        assertFalse(CatalogVisibility.visible(live, now + 300_000), "Stale ESPN data must not extend estimated expiry")
        assertFalse(CatalogVisibility.visible(live.copy(fixture = live.fixture!!.copy(state = MatchState.FINISHED)), now))
    }
}
