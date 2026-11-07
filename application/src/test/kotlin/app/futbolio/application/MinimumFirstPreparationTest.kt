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
class MinimumFirstPreparationTest {
    private val now = 1_800_000_000_000L
    private val config = PresentationConfig()
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No network")
    }
    private fun clock(scheduler: TestCoroutineScheduler) = object : TimeSource {
        override fun monotonicMillis() = scheduler.currentTime
        override fun wallMillis() = now + scheduler.currentTime
    }
    private fun entry(index: Int, providers: Int = 1) = CatalogEntry("match-$index", "Home $index vs Away $index",
        Leagues.find("Premier League"), now - 1000, (1..providers).map { provider ->
            Listing(SourceConfig("provider-$provider", "provider-$provider", "Provider $provider", "https://fixture.example/"),
                "Home $index vs Away $index", "Premier League", "https://fixture.example/m$index-p$provider", starts = now - 1000)
        }, Team("Home $index"), Team("Away $index"))
    private fun choice(item: Listing) = StreamChoice("Live", "${item.page}.m3u8")
    private fun directory(load: suspend (Listing) -> List<StreamChoice>) = object : ProviderDirectory {
        override val descriptors = emptyList<ProviderDescriptor>()
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(emptyList())
            override suspend fun resolve(item: Listing, net: Upstream) = load(item)
        }
    }
    private fun good() = StreamAssessment(ConnectionStatus.GOOD, samples = 2)

    @Test fun allTwentyPriorityListingsFinishBeforeProbesAndExpansionComesLast() = runTest {
        val entries = (1..6).map { entry(it, 5) }
        val prepared = mutableSetOf<String>()
        val timeline = mutableListOf<String>()
        var active = 0; var peak = 0
        val resolver = StreamResolver(backgroundScope, net, directory {
            active++; peak = maxOf(peak, active)
            try {
                delay(20)
                prepared += it.page
                timeline += "minimum:${it.page}"
                if (it.page.contains("m5-") || it.page.contains("m6-"))
                    assertEquals(12, timeline.count { event -> event.startsWith("probe:") }, "Expansion must follow all priority enrichment")
                listOf(choice(it))
            } finally { active-- }
        }, clock(testScheduler), assessor = StreamAssessor {
            assertEquals(20, prepared.size, "Every provider of all four targets—not just sixteen pages—must be ready first")
            timeline += "probe:${it.url}"
            delay(10)
            good()
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler))
        warmer.browsing(); warmer.warmOnce()
        assertEquals(30, prepared.size)
        assertEquals(2, peak)
        assertTrue(timeline.take(20).all { it.startsWith("minimum:") })
        assertEquals(12, timeline.count { it.startsWith("probe:") })
        assertTrue(entries.flatMap { it.listings }.all(resolver::hasFresh))
        assertTrue(warmer.lastReport.matches.all { it.minimumReadyMillis != null })
    }

    @Test fun discoveryAndEspnStageFinishBeforeAutomaticMinimumPreparation() = runTest {
        val discovery = CompletableDeferred<Unit>(); val espn = CompletableDeferred<Unit>()
        var loads = 0
        val resolver = StreamResolver(backgroundScope, net, directory { loads++; listOf(choice(it)) }, clock(testScheduler))
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { espn.await(); listOf(entry(1)) }, { config }, clock(testScheduler),
            initialize = { discovery.await() })
        warmer.start(); runCurrent()
        assertEquals(PreparationPhase.DISCOVERY, warmer.phase)
        assertEquals(0, loads)
        discovery.complete(Unit); runCurrent()
        assertEquals(0, loads)
        espn.complete(Unit); runCurrent()
        assertEquals(1, loads)
        warmer.stop()
    }

    @Test fun coldSelectionDoesNotWaitForStartupScoreboardAndIncludesFreeMetadata() = runTest {
        val gate = CompletableDeferred<Unit>()
        val selected = entry(9, 2)
        var probes = 0
        val resolver = StreamResolver(backgroundScope, net, directory {
            delay(10)
            listOf(choice(it).copy(assessment = StreamAssessment(resolution = "1280x720", bitrate = 3_000_000)))
        }, clock(testScheduler), assessor = StreamAssessor { probes++; good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { gate.await(); listOf(entry(1)) }, { config }, clock(testScheduler))
        warmer.start(); runCurrent()
        warmer.selected(selected)
        val streams = resolver.awaitPreparedSnapshot(selected.listings, resolver.revision()).filter { it.url.isNotBlank() }
        assertEquals(10L, testScheduler.currentTime)
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.assessment.resolution == "1280x720" && it.assessment.status == ConnectionStatus.UNKNOWN })
        assertEquals(0, probes)
        warmer.stop()
    }

    @Test fun coldSelectionPreemptsOtherMinimumFetchesAndPreservesCompletedResults() = runTest {
        val priority = entry(1, 3); val selected = entry(9, 2)
        val cancelled = mutableSetOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it.page.contains("m1-") && !it.page.endsWith("p1")) {
                try { awaitCancellation() } finally { cancelled += it.page }
            }
            if (it.page.contains("m9-")) delay(10)
            listOf(choice(it))
        }, clock(testScheduler))
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(priority) }, { config }, clock(testScheduler))
        warmer.start(); runCurrent()
        assertTrue(resolver.hasFresh(priority.listings.first()))
        warmer.selected(selected)
        val streams = resolver.awaitPreparedSnapshot(selected.listings, resolver.revision()).filter { it.url.isNotBlank() }
        assertEquals(2, streams.size)
        assertEquals(10L, testScheduler.currentTime)
        assertEquals(2, cancelled.size)
        assertTrue(resolver.hasFresh(priority.listings.first()), "Preemption retains completed minimum results")
        warmer.stop()
    }

    @Test fun coldSelectionCancelsAnActiveProbeWithoutWaitingForItsDeadline() = runTest {
        val priority = entry(1); val selected = entry(9, 2)
        var probeStarted = false; var probeCancelled = false
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it.page.contains("m9-")) delay(10)
            listOf(choice(it))
        }, clock(testScheduler), assessor = StreamAssessor {
            probeStarted = true
            try { awaitCancellation() } finally { probeCancelled = true }
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(priority) }, { config }, clock(testScheduler))
        warmer.start(); runCurrent()
        assertTrue(probeStarted)
        warmer.selected(selected)
        val response = resolver.awaitPreparedSnapshot(selected.listings, resolver.revision())
        assertEquals(10L, testScheduler.currentTime)
        assertTrue(probeCancelled)
        assertEquals(2, response.count { it.url.isNotBlank() })
        warmer.stop()
    }

    @Test fun unavailableAndTimedOutProvidersAreTerminalAttemptsNotAnEndlessBarrier() = runTest {
        val entries = (1..5).map { entry(it, 2) }
        var timedOut = false; var expanded = false
        val limits = ResourceLimits(resolutionTimeoutMillis = 300)
        val resolver = StreamResolver(backgroundScope, net, directory {
            when {
                it.page.endsWith("m1-p1") -> error("Unavailable")
                it.page.endsWith("m1-p2") -> try { awaitCancellation() } finally { timedOut = true }
                it.page.contains("m5-") -> expanded = true
            }
            listOf(choice(it))
        }, clock(testScheduler), limits, assessor = StreamAssessor { assertTrue(timedOut); good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler), limits)
        warmer.browsing(); warmer.warmOnce()
        assertTrue(timedOut)
        assertTrue(expanded)
        assertNotNull(resolver.preparedSnapshot(entries.first().listings, resolver.revision()))
    }

    @Test fun incompleteEnrichmentResumesBeforeAnyOtherMatchMinimumSetIsFetched() = runTest {
        val entries = (1..5).map { entry(it) }
        val completed = mutableSetOf<String>()
        var expanded = false
        val limits = ResourceLimits(prefetchBatchMillis = 15_000, streamAssessmentMillis = 10_000)
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it.page.contains("m5-")) {
                assertEquals(4, completed.size)
                expanded = true
            }
            listOf(choice(it))
        }, clock(testScheduler), limits, assessor = StreamAssessor {
            delay(8000)
            completed += it.url
            good()
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler), limits)
        warmer.browsing(); warmer.warmOnce()
        assertFalse(expanded)
        repeat(5) { if (!expanded) warmer.warmOnce() }
        assertTrue(expanded, "Completed enrichment must be retained across budgets instead of restarting the first provider forever")
    }

    @Test fun expiryDoesNotRestartTheInitialBarrierForever() = runTest {
        val entries = (1..5).map { entry(it) }
        var expanded = false
        val limits = ResourceLimits(resolutionCacheMillis = 100, prefetchRefreshAheadMillis = 20)
        val resolver = StreamResolver(backgroundScope, net, directory {
            delay(80)
            if (it.page.contains("m5-")) expanded = true
            listOf(choice(it))
        }, clock(testScheduler), limits, assessor = StreamAssessor { good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler), limits)
        warmer.browsing(); warmer.warmOnce()
        assertTrue(expanded)
        assertTrue(testScheduler.currentTime < 5000)
    }

    @Test fun expansionProgressesAcrossPassesWithoutEvictingPriorityMinimumSets() = runTest {
        val entries = (1..9).map { entry(it) }
        val loaded = mutableSetOf<String>()
        val limits = ResourceLimits(resolutionCacheEntries = 6, prefetchExpansionMatches = 2)
        val resolver = StreamResolver(backgroundScope, net, directory { loaded += it.page; listOf(choice(it)) }, clock(testScheduler), limits,
            assessor = StreamAssessor { good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler), limits)
        warmer.browsing()
        repeat(3) {
            warmer.warmOnce()
            assertTrue(entries.take(4).flatMap { it.listings }.all(resolver::hasFresh), "Expansion must not evict the protected priority cache")
        }
        assertEquals(9, loaded.size)
    }

    @Test fun playbackPausesBackgroundWorkAndIdlePreparationResumesWithoutAnotherBrowse() = runTest {
        var allowed = true; var loads = 0; var probes = 0
        val resolver = StreamResolver(backgroundScope, net, directory { loads++; listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { probes++; awaitCancellation() }, measurementAllowed = { allowed })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(entry(1)) }, { config }, clock(testScheduler))
        warmer.start(); runCurrent()
        assertEquals(1, probes)
        allowed = false; warmer.pause(); resolver.pauseMeasurements(); runCurrent()
        val pausedLoads = loads
        advanceTimeBy(100_000); runCurrent()
        assertEquals(pausedLoads, loads)
        allowed = true
        advanceTimeBy(5000); runCurrent()
        assertTrue(loads > pausedLoads)
        assertTrue(probes > 1)
        warmer.stop()
        val stopped = loads
        advanceTimeBy(100_000); runCurrent()
        assertEquals(stopped, loads)
    }
}
