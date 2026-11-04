package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.catalog.*
import app.futbolio.application.fixtures.FixtureService
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.time.Instant
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FixtureCatalogTest {
    private val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
    private class Clock : TimeSource {
        var wall = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
        var ticks = 0L
        override fun wallMillis() = wall
        override fun monotonicMillis() = ticks
    }
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No network expected")
    }
    private fun row(clock: Clock, id: String = "one", league: String = "Premier League", start: Long = clock.wall) =
        Listing(source.copy(id = id), "Arsenal vs Chelsea", league, "https://fixture.example/$id", starts = start)
    private fun entry(clock: Clock, id: String = "one", league: String = "Premier League", start: Long = clock.wall) =
        MatchNormalizer.aggregate(listOf(row(clock, id, league, start)), emptyList(), PresentationConfig(), clock.wall).single()
    private fun fixture(clock: Clock, league: League = Leagues.find("Premier League"), starts: Long = clock.wall, state: MatchState = MatchState.LIVE) =
        ScoreFixture("real", league, Team("Arsenal"), Team("Chelsea"), starts, state, "3", "2", "Score enrichment must be discarded", clock.wall)
    private fun directory(discover: suspend (SourceConfig) -> List<Listing>) = object : ProviderDirectory {
        override val descriptors = listOf(ProviderDescriptor(source))
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(discover(source))
            override suspend fun resolve(item: Listing, net: Upstream) = emptyList<StreamChoice>()
        }
    }

    @Test fun fixtureOnlyAdaptersDoNotSweepLeaguesDuringBrowsingAndReuseAggregation() = runTest {
        val clock = Clock()
        var calls = 0
        val fixtures = FixtureService(this, ScoreFeed { _, _, _ -> calls++; error("Unexpected scoreboard sweep") }, clock)
        val catalog = Catalog(this, net, listOf(source), directory { listOf(row(clock)) }, clock)
        catalog.refresh().await()
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(scoresEnabled = true), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        val first = entries.entries(includeHidden = true)
        repeat(20) { assertSame(first, entries.entries(includeHidden = true)) }
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(1, entries.entries().size)
        clock.wall += 2 * CatalogVisibility.HOUR
        assertTrue(entries.entries().isEmpty(), "Expiry must not wait for reaggregation or a provider refresh")
    }

    @Test fun coldCatalogReturnsImmediatelyWhileProviderIsBlocked() = runTest {
        val clock = Clock()
        val fixtures = FixtureService(backgroundScope, ScoreFeed { _, _, _ -> error("Unexpected fixture call") }, clock)
        val catalog = Catalog(backgroundScope, net, listOf(source), directory { awaitCancellation() }, clock)
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        val before = currentTime
        assertTrue(entries.entries().isEmpty())
        assertEquals(before, currentTime)
        assertTrue(catalog.status.value.single().loading)
    }

    @Test fun browsingWaitsForScoreboardButStreamSelectionNeverWaitsForIt() = runTest {
        val clock = Clock(); var scoreboardCalls = 0
        val feed = object : ScoreFeed {
            override suspend fun fixtures(league: League, date: java.time.LocalDate, observedAt: Long) = emptyList<ScoreFixture>()
            override suspend fun scoreboard(observedAt: Long): List<ScoreFixture> {
                scoreboardCalls++; delay(100)
                return listOf(fixture(clock).copy(detail = "47'", minute = "47'"))
            }
        }
        val fixtures = FixtureService(this, feed, clock)
        val catalog = Catalog(this, net, listOf(source), directory { listOf(row(clock)) }, clock)
        catalog.refresh().await()
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        val id = entry(clock).id
        val before = currentTime
        assertNotNull(entries.selected(id))
        assertEquals(before, currentTime)
        assertEquals(0, scoreboardCalls)
        val enriched = entries.entries().single()
        assertEquals(100, currentTime - before)
        assertEquals("3 - 2", enriched.fixture!!.scoreText(clock.wall))
        assertEquals("47'", entries.posterEntry(id)!!.fixture!!.clockText(clock.wall))
        assertEquals(1, scoreboardCalls)
    }

    @Test fun selectedMatchRequestsAreCoalescedAndScoresAreDiscarded() = runTest {
        val clock = Clock()
        var calls = 0
        val fixtures = FixtureService(this, ScoreFeed { league, _, _ -> calls++; delay(100); listOf(fixture(clock, league)) }, clock)
        val jobs = (1..20).map { fixtures.request(entry(clock), PresentationConfig()) }
        jobs.flatten().forEach { it.await() }
        assertEquals(1, calls)
        val result = fixtures.fixtures().single()
        assertEquals("", result.homeScore)
        assertEquals("", result.awayScore)
        assertEquals("", result.detail)
        assertTrue(fixtures.request(entry(clock), PresentationConfig()).isEmpty())
        clock.ticks += 60_000
        fixtures.request(entry(clock), PresentationConfig()).forEach { it.await() }
        assertEquals(2, calls)
    }

    @Test fun failuresBackOffAndDoNotLeakDetailsOrBlockPlayback() = runTest {
        val clock = Clock()
        var calls = 0
        val fixtures = FixtureService(this, ScoreFeed { _, _, _ -> calls++; error("signed-token-private") }, clock)
        fixtures.request(entry(clock), PresentationConfig()).forEach { it.await() }
        repeat(20) { fixtures.request(entry(clock), PresentationConfig()) }
        assertEquals(1, calls)
        assertFalse(fixtures.lastError.contains("signed-token"))
        assertTrue(fixtures.fixtures().isEmpty())
        clock.ticks += 300_000
        fixtures.request(entry(clock), PresentationConfig()).forEach { it.await() }
        assertEquals(2, calls)
    }

    @Test fun fixtureConcurrencyQueueAndDeadlineAreBounded() = runTest {
        val clock = Clock()
        var active = 0
        var maximum = 0
        var started = 0
        val fixtures = FixtureService(this, ScoreFeed { _, _, _ ->
            active++; started++; maximum = maxOf(maximum, active)
            try { awaitCancellation() } finally { active-- }
        }, clock)
        val jobs = Leagues.curated.map { fixtures.request(entry(clock, league = it.name), PresentationConfig()) }.flatten()
        assertEquals(12, jobs.size)
        jobs.forEach { it.await() }
        assertTrue(maximum <= 2)
        assertTrue(started <= 12)
        assertEquals(0, active)
        assertTrue(currentTime <= 1501)
    }

    @Test fun reconfigurationRejectsLateFixtureResults() = runTest {
        val clock = Clock()
        val entered = CompletableDeferred<Unit>()
        val fixtures = FixtureService(this, ScoreFeed { _, _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { delay(100) }
            listOf(fixture(clock))
        }, clock)
        fixtures.request(entry(clock), PresentationConfig())
        entered.await()
        fixtures.reset()
        advanceUntilIdle()
        assertTrue(fixtures.fixtures().isEmpty())
    }

    @Test fun conflictTriggersOnlyOneSharedLeagueDateLookupAndCorrectsAllProviderTimes() = runTest {
        val clock = Clock()
        var calls = 0
        val officialStart = clock.wall + CatalogVisibility.HOUR
        val fixtures = FixtureService(this, ScoreFeed { league, _, _ -> calls++; listOf(fixture(clock, league, officialStart, MatchState.SCHEDULED)) }, clock)
        val other = source.copy(id = "other")
        val catalog = Catalog(this, net, listOf(source, other), directory { configured ->
            listOf(row(clock, configured.id, start = if (configured.id == source.id) clock.wall else clock.wall + 6 * CatalogVisibility.HOUR))
        }, clock)
        catalog.refresh().await()
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        assertEquals(1, entries.entries().size)
        advanceUntilIdle()
        val result = entries.entries().single()
        assertEquals(1, calls)
        assertEquals(officialStart, result.starts)
        assertEquals(2, result.listings.size)
        assertFalse(result.needsFixtureLookup)
    }

    @Test fun selectedStatusHasShortWaitAndNeverBlocksStreamSelection() = runTest {
        val clock = Clock()
        val fixtures = FixtureService(backgroundScope, ScoreFeed { _, _, _ -> awaitCancellation() }, clock)
        val catalog = Catalog(this, net, listOf(source), directory { listOf(row(clock)) }, clock)
        catalog.refresh().await()
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        val id = entries.entries().single().id
        val before = currentTime
        assertNotNull(entries.selected(id, waitMillis = 350))
        assertEquals(350, currentTime - before)
        val streamBefore = currentTime
        assertNotNull(entries.selected(id))
        assertEquals(streamBefore, currentTime)
    }

    @Test fun finalResultSurvivesLaterMissingResponsesAndCatalogDisappears() = runTest {
        val clock = Clock()
        var final = true
        val fixtures = FixtureService(this, ScoreFeed { _, _, _ ->
            if (final) listOf(fixture(clock, state = MatchState.FINISHED)) else emptyList()
        }, clock)
        fixtures.request(entry(clock), PresentationConfig()).forEach { it.await() }
        final = false
        clock.ticks += 60_000
        fixtures.request(entry(clock), PresentationConfig()).forEach { it.await() }
        clock.wall += 10 * 60_000
        assertEquals(MatchState.FINISHED, fixtures.fixtures().single().state)
        val catalog = Catalog(this, net, listOf(source), directory { listOf(row(clock)) }, clock)
        catalog.refresh().await()
        val entries = EntryCatalog(catalog, fixtures, PresentationConfig(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        assertTrue(entries.entries().isEmpty())
    }
}
