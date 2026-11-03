package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.application.presentation.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderDashboardTest {
    private val source = SourceConfig("one", "fixture", "One", "https://fixture.example/")
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No network expected")
    }
    private fun directory(order: MutableList<String>, withStream: Boolean = true) = object : ProviderDirectory {
        override val descriptors = listOf(ProviderDescriptor(source))
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
                order += source.id
                return ProviderResult(listOf(Listing(source, "Home vs Away", "Premier League", "https://fixture.example/${source.id}", starts = 100_000)))
            }
            override suspend fun resolve(item: Listing, net: Upstream) = if (withStream) listOf(StreamChoice(item.source.id, "https://cdn.example/${item.source.id}.m3u8")) else emptyList()
        }
    }

    @Test fun testAllRunsEnabledProvidersSequentiallyAndRejectsDuplicateBatchClicks() = runTest {
        val order = mutableListOf<String>()
        var active = 0; var maximum = 0
        val providers = directory(order)
        val probe = object : MediaProbe {
            override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean): List<SegmentResult> {
                active++; maximum = maxOf(maximum, active)
                try { delay(50); if (choice.title == "one") error("Provider offline")
                    return listOf(SegmentResult(564, "MPEG-TS", "fixture"))
                } finally { active-- }
            }
        }
        val workbench = ProviderWorkbench(this, net, StreamResolver(this, net, providers), providers, probe)
        val sources = listOf(source, source.copy(id = "disabled", enabled = false), source.copy(id = "two"))
        val batch = assertNotNull(workbench.startAll(sources))
        assertNull(workbench.startAll(sources))
        assertSame(batch, workbench.start(source))
        batch.join()
        assertEquals(listOf("one", "two"), order)
        assertEquals(1, maximum)
        assertEquals(setOf("one", "two"), workbench.reports.value.keys)
        assertEquals(ProviderTestBatch(completed = 2, total = 2), workbench.batchState.value)
    }

    @Test fun configurationResetCancelsBatchAndCannotStartRemainingProviders() = runTest {
        val order = mutableListOf<String>()
        val providers = directory(order)
        val entered = CompletableDeferred<Unit>()
        val probe = object : MediaProbe {
            override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean): List<SegmentResult> { entered.complete(Unit); awaitCancellation() }
        }
        val workbench = ProviderWorkbench(this, net, StreamResolver(this, net, providers), providers, probe)
        val batch = assertNotNull(workbench.startAll(listOf(source, source.copy(id = "two"))))
        entered.await(); workbench.reset(); advanceUntilIdle()
        assertTrue(batch.isCancelled)
        assertEquals(listOf("one"), order)
        assertTrue(workbench.reports.value.isEmpty())
        assertEquals(ProviderTestBatch(), workbench.batchState.value)
    }

    @Test fun statusCardsNeverEquateCatalogOrSegmentsWithConfirmedPlayback() {
        val status = ProviderStatus(source.id, source.name, true, ok = true, count = 25, checked = 1)
        val runtime = RuntimeSnapshot(RuntimePhase.RUNNING, providers = listOf(status))
        val initial = providerCardState(source, runtime)
        assertEquals("Connected", initial.connected.text)
        assertEquals("25 listings", initial.catalog.text)
        assertEquals("Not checked", initial.fetching.text)
        val choice = StreamTestResult("choice", "Fixture", ProbeStage.SEGMENTS_FETCHED, listOf(SegmentResult(564, "MPEG-TS", "fixture")))
        fun withChoice(value: StreamTestResult) = runtime.copy(reports = mapOf(source.id to ProviderTestReport(source.id, source.name, 1, ProbePhase.COMPLETE,
            listings = listOf(ListingTestResult("fixture", "Fixture", listOf(value))))))
        assertEquals("True", providerCardState(source, withChoice(choice)).fetching.text)
        assertEquals("True", providerCardState(source, withChoice(choice.copy(playback = PlaybackStatus.FAILED_IN_PLAYER))).fetching.text)
        assertEquals("Fetch failed", providerCardState(source, withChoice(choice.copy(segments = emptyList(), playback = PlaybackStatus.CONFIRMED_BY_USER))).fetching.text)
        assertEquals("Fetch failed", providerCardState(source, withChoice(choice.copy(segments = listOf(SegmentResult(0, "MPEG-TS", "empty"))))).fetching.text)
        assertEquals("Not verified", providerCardState(source, withChoice(choice.copy(stage = ProbeStage.RESOLVED_DIRECT, segments = emptyList()))).fetching.text)
        assertEquals("Disabled", providerCardState(source.copy(enabled = false), runtime).connected.text)
        assertEquals("Relay stopped", providerCardState(source, runtime.copy(phase = RuntimePhase.STOPPED)).connected.text)
    }

    @Test fun unavailableSamplesAreNotPresentedAsFetchFailures() {
        fun runtime(report: ProviderTestReport) = RuntimeSnapshot(RuntimePhase.RUNNING, reports = mapOf(source.id to report))
        val report = ProviderTestReport(source.id, source.name, 1, ProbePhase.COMPLETE, catalogCount = 203, upcomingOnly = true)
        assertEquals(HealthValue("Waiting for live", HealthTone.NEUTRAL), providerCardState(source, runtime(report)).fetching)
        assertTrue(providerDetails(source, runtime(report)).any { it.second.contains("not a fetch failure") })
        assertEquals("No stream available", providerCardState(source, runtime(report.copy(upcomingOnly = false))).fetching.text)
        assertEquals("No listings", providerCardState(source, runtime(report.copy(upcomingOnly = false, catalogCount = 0))).fetching.text)
        val failed = StreamTestResult("choice", "HD", ProbeStage.FAILED, error = "HTTP 403")
        assertEquals("Fetch failed", providerCardState(source, runtime(report.copy(listings = listOf(ListingTestResult("match", "Match", listOf(failed)))))).fetching.text)
        assertEquals("Test failed", providerCardState(source, runtime(report.copy(phase = ProbePhase.FAILED))).fetching.text)
    }

    @Test fun workbenchReportsUpcomingOnlyWithoutPretendingAMediaProbeFailed() = runTest {
        val providers = directory(mutableListOf(), withStream = false)
        val time = object : TimeSource {
            override fun wallMillis() = 1L
            override fun monotonicMillis() = 1L
        }
        val probe = object : MediaProbe {
            override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean): List<SegmentResult> = error("No media should be probed")
        }
        val workbench = ProviderWorkbench(this, net, StreamResolver(this, net, providers), providers, probe, time)
        val report = workbench.test(source)
        assertEquals(ProbePhase.COMPLETE, report.phase)
        assertTrue(report.upcomingOnly)
        assertEquals("Waiting for live", providerCardState(source, RuntimeSnapshot(RuntimePhase.RUNNING, reports = mapOf(source.id to report))).fetching.text)
    }

    @Test fun detailsHideSignedUrlsAndShowFetchedEvidence() {
        assertEquals("https://cdn.example/…", safeProviderUrl("https://user:password@cdn.example/secret/path?token=private"))
        val choice = StreamTestResult("choice", "HD", ProbeStage.SEGMENTS_FETCHED, listOf(SegmentResult(564, "MPEG-TS", "hash")), mediaUrl = "https://cdn.example/…")
        val runtime = RuntimeSnapshot(RuntimePhase.RUNNING, reports = mapOf(source.id to ProviderTestReport(source.id, source.name, 1, ProbePhase.COMPLETE,
            listings = listOf(ListingTestResult("fixture", "Home vs Away", listOf(choice))))))
        val details = providerDetails(source, runtime).toString()
        assertTrue(details.contains("564 bytes"))
        assertTrue(details.contains("MPEG-TS"))
        assertTrue(details.contains("Stream Fetching · True"))
        assertTrue(details.contains("https://cdn.example/…"))
    }
}
