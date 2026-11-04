package app.futbolio.application

import app.futbolio.application.fixtures.FixtureService
import app.futbolio.application.ports.*
import app.futbolio.domain.match.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardServiceTest {
    private class Clock : TimeSource {
        var wall = Instant.parse("2026-11-05T20:00:00Z").toEpochMilli()
        var ticks = 0L
        override fun wallMillis() = wall
        override fun monotonicMillis() = ticks
    }
    private fun fixture(clock: Clock, state: MatchState = MatchState.LIVE) = ScoreFixture("espn", Leagues.find("Premier League"),
        Team("Arsenal"), Team("Chelsea"), clock.wall, state, "2", "1", "47'", clock.wall, "47'")
    private fun feed(block: suspend (Long) -> List<ScoreFixture>) = object : ScoreFeed {
        override suspend fun fixtures(league: League, date: LocalDate, observedAt: Long): List<ScoreFixture> = error("No per-league sweep")
        override suspend fun scoreboard(observedAt: Long) = block(observedAt)
    }

    @Test fun concurrentCatalogsShareOneRequestAndRefreshAfterThirtySeconds() = runTest {
        val clock = Clock(); var calls = 0
        val scores = FixtureService(this, feed { calls++; delay(100); listOf(fixture(clock)) }, clock)
        val results = (1..20).map { async { scores.scoreboard() } }.awaitAll()
        assertEquals(1, calls)
        assertTrue(results.all { it.single().homeScore == "2" && it.single().minute == "47'" })
        assertTrue(scores.fixtures().isEmpty(), "Score overlays must not change aggregation identity")
        scores.scoreboard(); assertEquals(1, calls)
        clock.ticks += 30_000
        scores.scoreboard(); assertEquals(2, calls)
    }

    @Test fun failuresDropOldLiveScoresBackOffAndDoNotLeakUpstreamDetails() = runTest {
        val clock = Clock(); var calls = 0
        val scores = FixtureService(this, feed {
            calls++
            if (calls > 1) error("private-signed-token")
            listOf(fixture(clock))
        }, clock)
        assertEquals(1, scores.scoreboard().size)
        clock.ticks += 30_000
        assertTrue(scores.scoreboard().isEmpty())
        assertFalse(scores.lastError.contains("private-signed-token"))
        repeat(20) { scores.scoreboard() }
        assertEquals(2, calls)
        clock.ticks += 15_000
        scores.scoreboard(); assertEquals(3, calls)
    }

    @Test fun blockedScoreboardHasBoundedDeadlineAndCallerCancellationIsNotSwallowed() = runTest {
        val scores = FixtureService(this, feed { awaitCancellation() }, Clock())
        val before = currentTime
        assertTrue(scores.scoreboard().isEmpty())
        assertEquals(1500, currentTime - before)
        assertTrue(scores.lastError.contains("timed out"))
        scores.reset()
        val caller = async { scores.scoreboard() }
        runCurrent(); caller.cancel()
        assertFailsWith<CancellationException> { caller.await() }
        scores.reset()
    }

    @Test fun resetRejectsLateResultsAndDoesNotFailTheCatalogCaller() = runTest {
        val clock = Clock(); val entered = CompletableDeferred<Unit>()
        val scores = FixtureService(this, feed {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(100) }
            listOf(fixture(clock))
        }, clock)
        val result = async { scores.scoreboard() }
        entered.await(); scores.reset(); advanceUntilIdle()
        assertTrue(result.await().isEmpty())
        assertTrue(scores.scoreboardSnapshot().isEmpty())
    }

    @Test fun confirmedEndSurvivesLaterMissingOrFailedResponses() = runTest {
        val clock = Clock(); var calls = 0
        val scores = FixtureService(this, feed {
            when (++calls) {
                1 -> listOf(fixture(clock, MatchState.FINISHED))
                2 -> emptyList()
                else -> error("Outage")
            }
        }, clock)
        assertEquals(MatchState.FINISHED, scores.scoreboard().single().state)
        repeat(2) {
            clock.ticks += 30_000
            assertEquals(MatchState.FINISHED, scores.scoreboard().single().state)
        }
    }
}
