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
import java.time.Instant
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStreamPrefetcherTest {
    private val now = Instant.parse("2026-11-07T16:30:00Z").toEpochMilli()
    private val config = PresentationConfig()
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("Unexpected transport")
    }
    private fun clock(scheduler: TestCoroutineScheduler) = object : TimeSource {
        override fun monotonicMillis() = scheduler.currentTime
        override fun wallMillis() = now + scheduler.currentTime
    }
    private fun row(id: String) = Listing(SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/"),
        "Arsenal vs Chelsea", "Premier League", "https://fixture.example/$id", starts = now - 60_000)
    private fun entry(id: String, league: String = "Premier League", home: String = "Home $id", providers: Int = 1) = CatalogEntry(
        id, "$home vs Away $id", Leagues.find(league), now - 60_000,
        (1..providers).map { index -> row("$id-$index").let { listing -> listing.copy(title = "$home vs Away $id", league = league,
            source = listing.source.copy(id = "provider-$index", kind = "provider-$index")) } }, Team(home), Team("Away $id"))
    private fun directory(resolve: suspend (Listing) -> List<StreamChoice>) = object : ProviderDirectory {
        override val descriptors = listOf(ProviderDescriptor(row("base").source))
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(emptyList())
            override suspend fun resolve(item: Listing, net: Upstream) = resolve(item)
        }
    }
    private fun choice(row: Listing) = StreamChoice("Live", "${row.page}.m3u8")
    private fun good() = StreamAssessment(ConnectionStatus.GOOD, headroom = 3.0, samples = 2)

    @Test fun preparationIsIndependentOfSamplingAndHighestPriorityMatchGetsUsefulResultsFirst() = runTest {
        val rows = listOf(entry("pl", providers = 3), entry("tur", "Süper Lig", providers = 3),
            entry("cl", "Champions League", providers = 3), entry("gs", "Süper Lig", "Galatasaray", 3), entry("other", "LaLiga"))
        val calls = mutableListOf<String>()
        var preparations = 0; var preparationPeak = 0
        var checks = 0; var checkPeak = 0
        var lastPreparedAt = 0L
        val resolver = StreamResolver(backgroundScope, net, directory { row ->
            preparations++; preparationPeak = maxOf(preparationPeak, preparations)
            try { delay(100); calls += row.page; lastPreparedAt = testScheduler.currentTime; listOf(choice(row)) } finally { preparations-- }
        }, clock(testScheduler), assessor = StreamAssessor {
            checks++; checkPeak = maxOf(checkPeak, checks)
            try { delay(200); good() } finally { checks-- }
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { rows }, { config }, clock(testScheduler))
        warmer.browsing()
        assertEquals(listOf("gs", "cl", "tur", "pl"), warmer.warmOnce())
        assertEquals(12, calls.size)
        assertEquals(listOf("gs-1", "gs-2", "gs-3"), calls.take(3).map { it.substringAfterLast('/') })
        assertEquals(2, preparationPeak)
        assertEquals(1, checkPeak, "Bandwidth tests must not race each other")
        assertTrue(rows.single { it.id == "gs" }.listings.all(resolver::hasFresh), "Finish the highest-priority match before expanding lower-priority matches")
        assertTrue(rows.single { it.id == "cl" }.listings.all(resolver::hasFresh))
        val first = warmer.lastReport.matches.first()
        assertEquals(100L, first.firstPreparedMillis)
        assertEquals(300L, first.firstRatedMillis)
        assertEquals(600L, lastPreparedAt, "All twelve preparations finish without waiting for twelve checks")
        assertTrue(requireNotNull(first.firstRatedMillis) < warmer.lastReport.elapsedMillis)
        val selected = rows.single { it.id == "gs" }.listings.first()
        val before = testScheduler.currentTime
        val cached = resolver.resolve(selected).first()
        assertEquals(before, testScheduler.currentTime)
        assertEquals(ConnectionStatus.GOOD, cached.assessment.status)
        assertTrue(cached.assessment.checkedAtMillis > 0)
        assertEquals(12, calls.size)
    }

    @Test fun selectedWarmupIsSharedAndPromotedRatherThanCanceledOrResolvedTwice() = runTest {
        var calls = 0
        val resolver = StreamResolver(backgroundScope, net, directory { calls++; delay(1000); listOf(choice(it)) },
            clock(testScheduler), assessor = StreamAssessor { delay(100); good() })
        val listing = row("shared")
        val warm = async { resolver.prefetch(listing, resolver.revision()) }
        runCurrent()
        advanceTimeBy(200)
        val selected = async { resolver.resolve(listing) }
        assertEquals(ConnectionStatus.GOOD, selected.await().first().assessment.status)
        assertEquals(selected.await(), warm.await())
        assertEquals(1, calls)
    }

    @Test fun measuresAtMostThreeDistinctProvidersButKeepsOtherLinksAvailable() = runTest {
        val candidate = entry("gs", "Süper Lig", "Galatasaray", providers = 5)
        val checked = mutableListOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory { delay(100); listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { checked += it.url; delay(100); good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(candidate) }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(3, checked.size)
        assertEquals(3, warmer.lastReport.matches.single().measuredProviders.size)
        assertTrue(candidate.listings.all(resolver::hasFresh), "Unmeasured providers still have prepared playable links")
        assertEquals(2, candidate.listings.count { listing -> resolver.prepare(listing, resolver.revision()).first().assessment.status == ConnectionStatus.UNKNOWN })
    }

    @Test fun duplicatePlayerPagesDoNotConsumeExtraProviderSlots() = runTest {
        val original = entry("gs", "Süper Lig", "Galatasaray", providers = 4)
        val candidate = original.copy(listings = original.listings.mapIndexed { index, listing ->
            listing.copy(source = listing.source.copy(kind = if (index < 2) "same-provider" else "other-$index"))
        })
        val checked = mutableListOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { checked += it.url; good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(candidate) }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(3, checked.size)
        assertEquals(setOf("same-provider", "other-2", "other-3"), warmer.lastReport.matches.single().measuredProviders)
    }

    @Test fun slowProviderPreparationDoesNotHideAnAlreadyReadySource() = runTest {
        val candidate = entry("gs", "Süper Lig", "Galatasaray", providers = 3)
        val resolver = StreamResolver(backgroundScope, net, directory {
            delay(if (it.source.kind == "provider-1") 20_000 else 100)
            listOf(choice(it))
        }, clock(testScheduler), assessor = StreamAssessor { delay(200); good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(candidate) }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        val timing = warmer.lastReport.matches.single()
        assertEquals(100L, timing.firstPreparedMillis)
        assertEquals(300L, timing.firstRatedMillis)
        assertEquals(20_000L, warmer.lastReport.elapsedMillis)
        assertEquals(setOf("provider-2", "provider-3"), timing.measuredProviders)
    }

    @Test fun initialOptionsAcrossMatchesComeBeforeRefinementsAndAreNotDownloadedTwice() = runTest {
        val entries = listOf(entry("gs", "Süper Lig", "Galatasaray", 2), entry("cl", "Champions League", providers = 2))
        val checked = mutableListOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory {
            listOf(choice(it).copy(title = "${it.page}-hd"), choice(it).copy(title = "${it.page}-sd", url = "${it.page}-sd.m3u8"))
        }, clock(testScheduler), assessor = StreamAssessor { checked += it.title; delay(100); good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(8, checked.size)
        assertEquals(8, checked.distinct().size)
        assertTrue(checked.take(4).all { it.endsWith("-hd") })
        assertTrue(checked.drop(4).all { it.endsWith("-sd") })
    }

    @Test fun expiredPreparedLinksAreNotResurrectedOrSilentlyReExtractedByMeasurement() = runTest {
        var preparations = 0; var checks = 0
        val listing = row("expires")
        val resolver = StreamResolver(backgroundScope, net, directory { preparations++; listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor { checks++; good() })
        resolver.prepare(listing, resolver.revision())
        advanceTimeBy(20_001)
        assertTrue(resolver.measurePrepared(listing, resolver.revision()).isEmpty())
        assertEquals(1, preparations)
        assertEquals(0, checks)
        assertFalse(resolver.hasFresh(listing))
    }

    @Test fun marginalFirstOptionGetsOneEarlyAlternativeWithinTheSamePriorityMatchBudget() = runTest {
        val entries = listOf(entry("gs", "Süper Lig", "Galatasaray"), entry("cl", "Champions League"))
        val checked = mutableListOf<String>()
        val resolver = StreamResolver(backgroundScope, net, directory {
            delay(100)
            listOf(choice(it).copy(title = "${it.page}-hd"), choice(it).copy(title = "${it.page}-sd", url = "${it.page}-sd.m3u8"))
        }, clock(testScheduler), assessor = StreamAssessor {
            checked += it.title
            delay(1000)
            if (it.title.endsWith("-sd")) good() else good().copy(status = ConnectionStatus.MEDIUM, headroom = 1.5)
        })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { entries }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        val first = warmer.lastReport.matches.first()
        assertEquals(1100L, first.firstRatedMillis)
        assertEquals(2100L, first.firstGoodMillis)
        assertEquals(listOf("https://fixture.example/gs-1-hd", "https://fixture.example/gs-1-sd"), checked.take(2))
        assertEquals(4, checked.distinct().size)
    }

    @Test fun stagedMeasurementHandlesMixedMediaTypesAndPublishesCheapMetadataBeforeGrade() = runTest {
        val listing = row("mixed")
        val checked = mutableListOf<String>()
        val assessor = object : StreamAssessor {
            override suspend fun assess(choice: StreamChoice) = error("Use progressive assessment")
            override suspend fun assess(choice: StreamChoice, update: (StreamAssessment) -> Unit): StreamAssessment {
                checked += choice.url
                update(StreamAssessment(resolution = "1920x1080"))
                delay(100)
                return good()
            }
        }
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(
            StreamChoice("Direct", "https://fixture.example/direct.mp4"), choice(it), choice(it).copy(url = "${it.page}-sd.m3u8"))
        }, clock(testScheduler), assessor = assessor)
        resolver.prepare(listing, resolver.revision())
        val measurement = async { resolver.measurePrepared(listing, resolver.revision()) }
        runCurrent()
        assertEquals("1920x1080", resolver.prepare(listing, resolver.revision())[1].assessment.resolution)
        assertFalse(resolver.hasAssessment(listing))
        measurement.await()
        assertTrue(resolver.hasAssessment(listing))
        assertFalse(resolver.hasAssessment(listing, 2))
        resolver.measurePrepared(listing, resolver.revision(), choices = 2)
        assertEquals(2, checked.size)
        assertTrue(resolver.hasAssessment(listing, 2))
    }

    @Test fun warmCacheExpiresFromItsOldestMeasurementUsingMonotonicTime() = runTest {
        var wall = now
        val time = object : TimeSource {
            override fun monotonicMillis() = testScheduler.currentTime
            override fun wallMillis() = wall
        }
        var checked = 0
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it), choice(it).copy(url = "${it.page}-sd.m3u8")) },
            time, assessor = StreamAssessor { delay(if (checked++ == 0) 200 else 2200); good() })
        val listing = row("freshness")
        resolver.prefetch(listing, resolver.revision())
        assertEquals(2400L, testScheduler.currentTime)
        wall += 3_600_000 // Wall-clock changes cannot prematurely invalidate or extend the cache.
        advanceTimeBy(57_799)
        assertTrue(resolver.hasFresh(listing))
        advanceTimeBy(1)
        assertFalse(resolver.hasFresh(listing), "TTL starts at the oldest observed sample, not batch completion")
    }

    @Test fun selectingAMatchPromotesAllItsProvidersBeforeIndividualRequestsStart() = runTest {
        var calls = 0
        val resolver = StreamResolver(backgroundScope, net, directory { calls++; delay(1000); listOf(choice(it)) },
            clock(testScheduler), assessor = StreamAssessor { good() })
        val listings = listOf(row("provider-a"), row("provider-b"))
        val warm = listings.map { async { resolver.prefetch(it, resolver.revision()) } }
        runCurrent()
        resolver.prioritize(listings, resolver.revision())
        val selected = listings.map { async { resolver.resolve(it) } }.awaitAll()
        assertEquals(2, calls)
        assertEquals(selected, warm.awaitAll())
    }

    @Test fun canceledSpeculationReleasesPendingAdmissionSlotsImmediately() = runTest {
        val limits = ResourceLimits(activeResolutions = 1, pendingResolutions = 2)
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it.page.contains("warm")) awaitCancellation() else listOf(choice(it))
        }, clock(testScheduler), limits, assessor = StreamAssessor { good() })
        val a = async { resolver.prefetch(row("warm-a"), resolver.revision()) }
        val b = async { resolver.prefetch(row("warm-b"), resolver.revision()) }
        runCurrent()
        assertEquals("https://fixture.example/foreground.m3u8", resolver.resolve(row("foreground")).first().url)
        assertFailsWith<CancellationException> { a.await() }
        assertFailsWith<CancellationException> { b.await() }
    }

    @Test fun foregroundCancelsOtherSpeculationAndDoesNotWaitBehindItsProviderSlot() = runTest {
        var cancelled = false
        val limits = ResourceLimits(activeResolutions = 1)
        val resolver = StreamResolver(backgroundScope, net, directory {
            if (it.page.endsWith("warm")) try { awaitCancellation() } finally { cancelled = true }
            else { delay(10); listOf(choice(it)) }
        }, clock(testScheduler), limits, assessor = StreamAssessor { good() })
        val warm = async { resolver.prefetch(row("warm"), resolver.revision()) }
        runCurrent()
        val selected = resolver.resolve(row("selected"))
        assertTrue(cancelled)
        assertEquals(10L, testScheduler.currentTime)
        assertEquals(ConnectionStatus.GOOD, selected.first().assessment.status)
        assertFailsWith<CancellationException> { warm.await() }
    }

    @Test fun joiningWarmupNeverInheritsTheLongerBackgroundDeadline() = runTest {
        val limits = ResourceLimits(streamAssessmentMillis = 30_000)
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler), limits,
            assessor = StreamAssessor { awaitCancellation() })
        val listing = row("shared")
        val warm = async { resolver.prefetch(listing, resolver.revision()) }
        runCurrent()
        advanceTimeBy(100)
        val selected = resolver.resolve(listing)
        assertEquals(7100L, testScheduler.currentTime)
        assertEquals("${listing.page}.m3u8", selected.first().url)
        assertEquals(ConnectionStatus.UNKNOWN, selected.first().assessment.status)
        assertEquals(selected, warm.await())
    }

    @Test fun playbackCancelsMeasurementsWithoutLosingForegroundChoicesAndPreventsNewChecks() = runTest {
        var allowed = true
        var cancelled = false
        var calls = 0
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler),
            assessor = StreamAssessor {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            }, measurementAllowed = { allowed })
        val selected = async { resolver.resolve(row("selected")) }
        runCurrent()
        allowed = false
        resolver.pauseMeasurements()
        assertEquals("https://fixture.example/selected.m3u8", selected.await().first().url)
        assertTrue(cancelled)
        assertEquals(ConnectionStatus.UNKNOWN, selected.await().first().assessment.status)
        resolver.resolve(row("another"))
        assertEquals(1, calls)
        assertTrue(resolver.prefetch(row("warm"), resolver.revision()).isEmpty())
    }

    @Test fun interestExpiresAndResetRevisionChangesPreventStaleWarmupWork() = runTest {
        var snapshots = 0; var calls = 0
        val resolver = StreamResolver(backgroundScope, net, directory { calls++; listOf(choice(it)) }, clock(testScheduler), assessor = StreamAssessor { good() })
        val entries = listOf(entry("gs", "Süper Lig", "Galatasaray"))
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { snapshots++; entries }, { config }, clock(testScheduler))
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(1, calls)
        advanceTimeBy(90_001)
        assertTrue(warmer.warmOnce().isEmpty())
        assertEquals(1, snapshots)
        warmer.browsing()
        warmer.warmOnce()
        assertEquals(2, calls)
        warmer.reset()
        resolver.trim()
        assertTrue(warmer.warmOnce().isEmpty())
        assertEquals(2, calls)
    }

    @Test fun tickerStopsAfterInterestWindowAndShutdown() = runTest {
        var snapshots = 0
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler), assessor = StreamAssessor { good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { snapshots++; emptyList() }, { config }, clock(testScheduler))
        warmer.start()
        runCurrent()
        assertEquals(1, snapshots)
        advanceTimeBy(95_000)
        runCurrent()
        val afterInterest = snapshots
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(afterInterest, snapshots)
        warmer.browsing()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(snapshots > afterInterest)
        warmer.stop()
        val stopped = snapshots
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(stopped, snapshots)
    }

    @Test fun configurationRevisionChangingDuringSnapshotCannotStartObsoleteProviderWork() = runTest {
        var calls = 0
        val resolver = StreamResolver(backgroundScope, net, directory { calls++; listOf(choice(it)) }, clock(testScheduler), assessor = StreamAssessor { good() })
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, {
            resolver.trim()
            listOf(entry("gs", "Süper Lig", "Galatasaray"))
        }, { config }, clock(testScheduler))
        warmer.browsing()
        assertTrue(warmer.warmOnce().isEmpty())
        assertEquals(0, calls)
    }

    @Test fun resetCancelsAnActiveBatchAndDoesNotPublishOrRestartItsWork() = runTest {
        var calls = 0; var cancelled = false
        val resolver = StreamResolver(backgroundScope, net, directory {
            calls++
            try { awaitCancellation() } finally { cancelled = true }
        }, clock(testScheduler), assessor = StreamAssessor { good() })
        val candidate = entry("gs", "Süper Lig", "Galatasaray")
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { listOf(candidate) }, { config }, clock(testScheduler))
        warmer.browsing()
        val batch = async { warmer.warmOnce() }
        runCurrent()
        warmer.reset()
        batch.await()
        assertTrue(cancelled)
        assertFalse(resolver.hasFresh(candidate.listings.first()))
        assertTrue(warmer.warmOnce().isEmpty())
        assertEquals(1, calls)
    }

    @Test fun alreadyStoppedScopeCannotLeavePreparationAwaitingForever() = runTest {
        val stopped = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        stopped.cancel()
        val resolver = StreamResolver(stopped, net, directory { error("Stopped resolver must not load") }, clock(testScheduler))
        assertFailsWith<CancellationException> { withTimeout(1000) { resolver.resolve(row("closed")) } }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun catalogUpdatesWakePreparationWithoutWaitingOrExtendingBrowsingInterest() = runTest {
        var snapshots = 0
        val resolver = StreamResolver(backgroundScope, net, directory { listOf(choice(it)) }, clock(testScheduler))
        val warmer = LiveStreamPrefetcher(backgroundScope, resolver, { snapshots++; emptyList() }, { config }, clock(testScheduler))
        warmer.start()
        runCurrent()
        assertEquals(1, snapshots)
        advanceTimeBy(1000)
        warmer.changed()
        runCurrent()
        assertEquals(2, snapshots, "React to provider discovery before the five-second maintenance tick")
        advanceTimeBy(90_000)
        runCurrent()
        val expired = snapshots
        warmer.changed()
        runCurrent()
        assertEquals(expired, snapshots, "Catalog changes alone must not authorize indefinite probing")
        warmer.stop()
    }
}
