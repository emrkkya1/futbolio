package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.runtime.FutbolioEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class MinimumFirstStartupTest {
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No external network")
    }
    private fun port() = ServerSocket(0).use { it.localPort }

    @Test fun realEngineWaitsForEveryDiscoveryAndUsesEspnStatusesBeforeFetchingMinimumSets() = runBlocking {
        val now = System.currentTimeMillis()
        val discoveryGate = CompletableDeferred<Unit>(); val discoveryStarted = CompletableDeferred<Unit>()
        val fastDiscoveryDone = CompletableDeferred<Unit>(); val scoreboardGate = CompletableDeferred<Unit>()
        val scoreboardStarted = CompletableDeferred<Unit>(); val firstResolved = CompletableDeferred<Unit>()
        val resolved = ConcurrentHashMap.newKeySet<String>()
        val sources = (1..2).map { SourceConfig("source-$it", "source-$it", "Source $it", "https://fixture.example/") }
        val directory = object : ProviderDirectory {
            override val descriptors = sources.map(::ProviderDescriptor)
            override fun get(kind: String) = object : Provider {
                override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
                    if (source.id == "source-1") { discoveryStarted.complete(Unit); discoveryGate.await() }
                    else fastDiscoveryDone.complete(Unit)
                    return ProviderResult(listOf(
                        Listing(source, "Arsenal vs Chelsea", "Premier League", "${source.url}ended", starts = now - 60_000),
                        Listing(source, "Manchester United vs Liverpool", "Premier League", "${source.url}live", starts = now + 3_600_000),
                    ))
                }
                override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
                    resolved += item.title
                    firstResolved.complete(Unit)
                    return listOf(StreamChoice("Direct", "https://media.example/${item.source.id}/live.mp4"))
                }
            }
        }
        val feed = object : ScoreFeed {
            override suspend fun fixtures(league: League, date: java.time.LocalDate, observedAt: Long) = emptyList<ScoreFixture>()
            override suspend fun scoreboard(observedAt: Long): List<ScoreFixture> {
                scoreboardStarted.complete(Unit)
                scoreboardGate.await()
                return listOf(
                    ScoreFixture("ended", Leagues.find("Premier League"), Team("Arsenal"), Team("Chelsea"), now - 60_000,
                        MatchState.FINISHED, "1", "0", "FT", observedAt),
                    ScoreFixture("live", Leagues.find("Premier League"), Team("Manchester United"), Team("Liverpool"), now + 3_600_000,
                        MatchState.LIVE, "0", "0", "Live", observedAt),
                )
            }
        }
        val engine = FutbolioEngine(HostConfig(port()), sources, PosterRenderer { _, _ -> byteArrayOf() }, net,
            providers = directory, scoreFeed = feed)
        try {
            engine.start()
            assertEquals(RuntimePhase.RUNNING, engine.phase.value, "The listener lifecycle must not wait for UI readiness")
            val preparingSnapshot = withTimeout(3000) { engine.snapshot.first { it.phase == RuntimePhase.RUNNING } }
            assertFalse(preparingSnapshot.preparation.ready)
            withTimeout(3000) { discoveryStarted.await(); fastDiscoveryDone.await() }
            assertTrue(resolved.isEmpty())
            assertFalse(scoreboardStarted.isCompleted, "The initial ESPN pass follows provider discovery/grouping")
            discoveryGate.complete(Unit)
            withTimeout(3000) { scoreboardStarted.await() }
            assertTrue(resolved.isEmpty(), "Minimum extraction must not race the initial live-status request")
            scoreboardGate.complete(Unit)
            withTimeout(3000) { firstResolved.await() }
            assertTrue(resolved.all { it.contains("Liverpool") }, "ESPN-ended matches must not be prepared from schedule estimates")
            assertTrue(resolved.isNotEmpty(), "ESPN-live matches must be prepared even if the provider schedule says upcoming")
            withTimeout(3000) { engine.snapshot.first { it.preparation.ready } }
            Unit
        } finally { engine.shutdown() }
    }

    private fun failedEspn(timeout: Boolean) = runBlocking {
        val now = System.currentTimeMillis()
        val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
        val resolved = CompletableDeferred<Unit>()
        var scoreAttempts = 0
        val directory = object : ProviderDirectory {
            override val descriptors = listOf(ProviderDescriptor(source))
            override fun get(kind: String) = object : Provider {
                override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(
                    Listing(source, "Arsenal vs Chelsea", "Premier League", source.url, starts = now - 60_000)))
                override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
                    assertTrue(scoreAttempts > 0)
                    resolved.complete(Unit)
                    return listOf(StreamChoice("Direct", "https://media.example/live.mp4"))
                }
            }
        }
        val feed = object : ScoreFeed {
            override suspend fun fixtures(league: League, date: java.time.LocalDate, observedAt: Long) = emptyList<ScoreFixture>()
            override suspend fun scoreboard(observedAt: Long): List<ScoreFixture> {
                scoreAttempts++
                if (timeout) awaitCancellation() else error("Scoreboard unavailable")
            }
        }
        val engine = FutbolioEngine(HostConfig(port()), listOf(source), PosterRenderer { _, _ -> byteArrayOf() }, net,
            providers = directory, scoreFeed = feed)
        try {
            engine.start()
            withTimeout(4000) { resolved.await() }
            assertEquals(1, scoreAttempts, "ESPN failure must fail open and use its negative-cache backoff")
        } finally { engine.shutdown() }
    }

    @Test fun unavailableEspnFallsBackToScheduleWithoutBlockingStreamPreparation() = failedEspn(false)
    @Test fun stalledEspnFallsBackAfterItsBoundedDeadline() = failedEspn(true)
}
