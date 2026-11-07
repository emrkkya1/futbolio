package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyStreamSnapshotTest {
    private val now = 1_800_000_000_000L
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No network")
    }
    private fun row(id: String) = Listing(SourceConfig(id, id, id, "https://fixture.example/"),
        "Arsenal vs Chelsea", "Premier League", "https://fixture.example/$id", starts = now - 1000)
    private fun choice(row: Listing) = StreamChoice("Live", "${row.page}.m3u8")
    private fun directory(load: suspend (Listing) -> List<StreamChoice>) = object : ProviderDirectory {
        override val descriptors = emptyList<ProviderDescriptor>()
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(emptyList())
            override suspend fun resolve(item: Listing, net: Upstream) = load(item)
        }
    }
    private fun clock(scheduler: TestCoroutineScheduler) = object : TimeSource {
        override fun monotonicMillis() = scheduler.currentTime
        override fun wallMillis() = now + scheduler.currentTime
    }
    private fun entry(vararg rows: Listing) = CatalogEntry("match", "Arsenal vs Chelsea", Leagues.find("Premier League"),
        now - 1000, rows.toList(), Team("Arsenal"), Team("Chelsea"))

    @Test fun coldResponseIncludesEveryProviderAfterLinkExtractionWithoutSampling() = runTest {
        val fast = row("fast"); val slow = row("slow")
        val resolver = StreamResolver(backgroundScope, net, directory {
            delay(if (it == fast) 100 else 10_000); listOf(choice(it))
        }, clock(testScheduler))
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { emptyList() }, { PresentationConfig() }, clock(testScheduler))
        warmer.start()
        warmer.selected(entry(fast, slow))
        val first = resolver.awaitPreparedSnapshot(listOf(fast, slow), resolver.revision())
        assertEquals(10_000L, testScheduler.currentTime, "Only necessary link extraction may delay the response")
        assertEquals(listOf("fast", "slow"), first.filter { it.url.isNotBlank() }.map { it.providers.single() })
        val at = testScheduler.currentTime
        assertEquals(first, resolver.awaitPreparedSnapshot(listOf(fast, slow), resolver.revision()))
        assertEquals(at, testScheduler.currentTime)
        assertEquals(2, resolver.preparedSnapshot(listOf(fast, slow), resolver.revision())!!.count { it.url.isNotBlank() })
        warmer.stop()
    }

    @Test fun inFlightObservationsAreVisibleInNewSnapshotsAndOldSnapshotsStayImmutable() = runTest {
        val listing = row("source")
        val assessor = object : StreamAssessor {
            override suspend fun assess(choice: StreamChoice) = error("Use updates")
            override suspend fun assess(choice: StreamChoice, update: (StreamAssessment) -> Unit): StreamAssessment {
                update(StreamAssessment(resolution = "1920x1080"))
                delay(5000)
                return StreamAssessment(ConnectionStatus.GOOD, resolution = "1920x1080", samples = 2)
            }
        }
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler), assessor = assessor)
        resolver.prepare(listing, resolver.revision())
        val old = resolver.preparedSnapshot(listOf(listing), resolver.revision())!!
        val check = async { resolver.measurePrepared(listing, resolver.revision()) }
        runCurrent()
        val partial = resolver.awaitPreparedSnapshot(listOf(listing), resolver.revision())
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("1920x1080", partial.first().assessment.resolution)
        assertEquals("", old.first().assessment.resolution)
        assertEquals(ConnectionStatus.UNKNOWN, partial.first().assessment.status)
        check.await()
        assertEquals(ConnectionStatus.GOOD, resolver.preparedSnapshot(listOf(listing), resolver.revision())!!.first().assessment.status)
        assertEquals(ConnectionStatus.UNKNOWN, partial.first().assessment.status)
    }

    @Test fun proactiveRefreshKeepsReadyDataAndReusesOnlyMatchingFreshEvidence() = runTest {
        val listing = row("refresh")
        var loads = 0; var checks = 0
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (loads++ > 0) delay(1000)
            listOf(choice(it))
        }, clock(testScheduler), assessor = StreamAssessor { checks++; StreamAssessment(ConnectionStatus.GOOD, samples = 2) })
        resolver.prefetch(listing, resolver.revision())
        val original = resolver.preparedSnapshot(listOf(listing), resolver.revision())!!
        advanceTimeBy(10_000)
        val refresh = async { resolver.refreshPrepared(listing, resolver.revision()) }
        runCurrent()
        assertEquals(original, resolver.awaitPreparedSnapshot(listOf(listing), resolver.revision()))
        refresh.await()
        resolver.measurePrepared(listing, resolver.revision())
        assertEquals(2, loads)
        assertEquals(1, checks)
        assertEquals(original.first().assessment.checkedAtMillis,
            resolver.preparedSnapshot(listOf(listing), resolver.revision())!!.first().assessment.checkedAtMillis)
    }

    @Test fun changedUrlDoesNotInheritAnOldGradeAndExpiredLinksAreNeverServed() = runTest {
        val listing = row("renew")
        var loads = 0
        val resolver = StreamResolver(backgroundScope, net, directory {
            loads++
            if (loads > 1) delay(15_000)
            listOf(choice(it).copy(url = "${it.page}/$loads.m3u8"))
        }, clock(testScheduler), assessor = StreamAssessor { StreamAssessment(ConnectionStatus.GOOD, samples = 2) })
        resolver.prefetch(listing, resolver.revision())
        advanceTimeBy(10_000)
        val refresh = async { resolver.refreshPrepared(listing, resolver.revision()) }
        runCurrent()
        advanceTimeBy(10_001)
        assertNull(resolver.preparedSnapshot(listOf(listing), resolver.revision()))
        refresh.await()
        val result = resolver.preparedSnapshot(listOf(listing), resolver.revision())!!.first()
        assertTrue(result.url.endsWith("/2.m3u8"))
        assertEquals(ConnectionStatus.UNKNOWN, result.assessment.status)
    }

    @Test fun maintenanceRefreshesLinksWhileTheMeasurementLaneIsBlocked() = runTest {
        val listing = row("live")
        var loads = 0; var probes = 0
        val resolver = StreamResolver(backgroundScope, net, directory { loads++; listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { probes++; awaitCancellation() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(entry(listing)) }, { PresentationConfig() }, clock(testScheduler))
        warmer.start()
        advanceTimeBy(25_000); runCurrent()
        assertTrue(loads >= 2, "Link maintenance must run independently of sampling batches")
        assertNotNull(resolver.preparedSnapshot(listOf(listing), resolver.revision()))
        assertTrue(probes > 0)
        warmer.stop()
    }

    @Test fun coldDemandCanResolveDuringPlaybackButCannotStartABandwidthCheck() = runTest {
        val listing = row("switch")
        var probes = 0
        val resolver = StreamResolver(backgroundScope, net, directory { delay(100); listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { probes++; StreamAssessment() }, measurementAllowed = { false })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { emptyList() }, { PresentationConfig() }, clock(testScheduler))
        warmer.start()
        warmer.selected(entry(listing))
        assertTrue(resolver.awaitPreparedSnapshot(listOf(listing), resolver.revision()).any { it.url.isNotBlank() })
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(0, probes)
        warmer.stop()
    }

    @Test fun settingsInvalidationCancelsColdExtractionWithoutPublishingOldResults() = runTest {
        val listing = row("old")
        val resolver = StreamResolver(backgroundScope, net, directory { awaitCancellation() }, clock(testScheduler))
        val revision = resolver.revision()
        val pending = async { resolver.awaitPreparedSnapshot(listOf(listing), revision) }
        runCurrent()
        resolver.trim()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun freshProviderBudgetSurvivesDifferentCompletionOrderOnLaterPasses() = runTest {
        val listings = (1..4).map { row("provider-$it") }
        val checked = mutableListOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it == listings.first()) delay(1000)
            listOf(choice(it))
        }, clock(testScheduler), assessor = StreamAssessor {
            checked += it.url
            StreamAssessment(ConnectionStatus.GOOD, samples = 2)
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(entry(*listings.toTypedArray())) },
            { PresentationConfig() }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(3, checked.size)
        assertTrue(checked.none { it == "${listings.first().page}.m3u8" })
        warmer.warmOnce()
        assertEquals(3, checked.size, "Later readiness order cannot consume a fourth fresh provider slot")
    }

    @Test fun sameUrlWithChangedCredentialsCannotReuseOldEvidence() = runTest {
        val listing = row("auth")
        var loads = 0
        val resolver = StreamResolver(backgroundScope, net, directory {
            loads++
            listOf(choice(it).copy(headers = SourceHeaders(referer = "https://fixture.example/player-$loads")))
        }, clock(testScheduler), assessor = StreamAssessor { StreamAssessment(ConnectionStatus.GOOD, samples = 2) })
        resolver.prefetch(listing, resolver.revision())
        advanceTimeBy(10_000)
        resolver.refreshPrepared(listing, resolver.revision())
        assertEquals(ConnectionStatus.UNKNOWN, resolver.preparedSnapshot(listOf(listing), resolver.revision())!!.first().assessment.status)
        assertFalse(resolver.hasAssessment(listing))
    }

    @Test fun partialCacheNeverHidesUnpreparedProvidersAndCannotWaitForExtraMetadata() = runTest {
        val ready = row("ready"); val cold = row("cold")
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it == cold) delay(2500)
            listOf(choice(it), choice(it).copy(url = "${it.page}-alternate.m3u8"))
        }, clock(testScheduler), assessor = StreamAssessor { awaitCancellation() })
        resolver.prepare(ready, resolver.revision())
        val measuring = async { resolver.measurePrepared(ready, resolver.revision()) }
        runCurrent()
        assertNull(resolver.preparedSnapshot(listOf(ready, cold), resolver.revision()), "A partial cache is not the full stream list")
        val streams = resolver.awaitPreparedSnapshot(listOf(ready, cold), resolver.revision()).filter { it.url.isNotBlank() }
        assertEquals(4, streams.size, "Every option from both providers must be listed")
        assertEquals(setOf("ready", "cold"), streams.flatMap { it.providers }.toSet())
        assertEquals(2500L, testScheduler.currentTime, "Response waits for links only, never the six-second check")
        assertTrue(measuring.isActive)
        assertTrue(streams.all { it.assessment.status == ConnectionStatus.UNKNOWN })
        measuring.cancelAndJoin()
        resolver.cancelPrefetches()
    }

    @Test fun fullyPreparedResponseReturnsEveryOptionImmediatelyWhileChecksAreBlocked() = runTest {
        val rows = (1..5).map { row("source-$it") }
        val resolver = StreamResolver(backgroundScope, net, directory {
            listOf(choice(it), choice(it).copy(url = "${it.page}-alternate.m3u8"))
        }, clock(testScheduler), assessor = StreamAssessor { awaitCancellation() })
        rows.forEach { resolver.prepare(it, resolver.revision()) }
        val measurement = async { resolver.measurePrepared(rows.first(), resolver.revision()) }
        runCurrent()
        val result = resolver.awaitPreparedSnapshot(rows, resolver.revision()).filter { it.url.isNotBlank() }
        assertEquals(10, result.size)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(measurement.isActive)
        assertTrue(result.all { it.assessment.status == ConnectionStatus.UNKNOWN })
        measurement.cancelAndJoin()
        resolver.cancelPrefetches()
    }

    @Test fun slowExtractionDoesNotDiscardAnotherProvidersAlreadyExtractedPlayableOptions() = runTest {
        val fast = row("fast"); val slow = row("slow")
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it == slow) delay(200)
            listOf(choice(it))
        }, clock(testScheduler), ResourceLimits(resolutionCacheMillis = 100))
        val response = resolver.awaitPreparedSnapshot(listOf(fast, slow), resolver.revision()).filter { it.url.isNotBlank() }
        assertEquals(2, response.size, "Cache TTL during another provider's extraction cannot drop a source from this request")
        assertEquals(setOf("${fast.page}.m3u8", "${slow.page}.m3u8"), response.map { it.url }.toSet())
    }
}
