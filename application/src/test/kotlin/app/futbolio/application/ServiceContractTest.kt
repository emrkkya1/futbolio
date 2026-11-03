package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.catalog.Catalog
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.application.fixtures.FixtureService
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceContractTest {
    private val source = SourceConfig("future-instance", "future-adapter", "Future", "https://fixture.example/")
    private val row = Listing(source, "Home vs Away", "Premier League", "https://fixture.example/game", starts = 100_000)
    private val noNetwork = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("Unexpected transport request")
    }
    private class Clock : TimeSource {
        var wall = 100_000L
        var ticks = 0L
        override fun wallMillis() = wall
        override fun monotonicMillis() = ticks
    }
    private fun directory(adapter: Provider) = object : ProviderDirectory {
        override val descriptors = listOf(ProviderDescriptor(source))
        override fun get(kind: String): Provider { require(kind == source.kind); return adapter }
    }
    private fun provider(discover: suspend () -> ProviderResult = { ProviderResult(listOf(row)) }) = object : Provider {
        override suspend fun discover(source: SourceConfig, net: Upstream) = discover()
        override suspend fun resolve(item: Listing, net: Upstream) = listOf(StreamChoice("Fixture", "https://media.example/live.m3u8"))
    }

    @Test fun unknownFutureAdapterWorksThroughValidationCatalogAndResolverWithoutEngine() = runTest {
        val providers = directory(provider())
        val clock = Clock()
        ConfigurationValidator(providers).validate(FutbolioConfig(sources = listOf(source)))
        val catalog = Catalog(this, noNetwork, listOf(source), providers, clock)
        catalog.refresh().await()
        assertEquals(listOf(row), catalog.items())
        assertEquals(1, catalog.status.value.single().count)
        val choices = StreamResolver(this, noNetwork, providers, clock).resolve(catalog.items().single())
        assertEquals("https://media.example/live.m3u8", choices.first().url)
        assertEquals(row.page, choices.last().externalUrl)
        assertFailsWith<IllegalArgumentException> { ConfigurationValidator(providers).validateSource(source.copy(kind = "absent")) }
    }

    @Test fun staleCatalogExpiryUsesElapsedTimeAndWallClockJumpsDoNotRefreshOrExtendIt() = runTest {
        var offline = false
        var calls = 0
        val clock = Clock()
        val providers = directory(provider { calls++; if (offline) error("Offline") else ProviderResult(listOf(row)) })
        val catalog = Catalog(this, noNetwork, listOf(source), providers, clock)
        catalog.refresh().await()
        clock.wall += 100_000
        catalog.refresh().await()
        assertEquals(1, calls)
        offline = true
        catalog.refresh(true).await()
        assertEquals(1, catalog.items().size)
        clock.wall = 0
        clock.ticks = 600_001
        assertTrue(catalog.items().isEmpty())
    }

    @Test fun multipleInstancesOfOneKindRetainIndependentListingAndResolutionIdentities() = runTest {
        val other = source.copy(id = "another-instance")
        var resolutions = 0
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(row.copy(source = source)))
            override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
                resolutions++
                return listOf(StreamChoice(item.source.id, "https://media.example/${item.source.id}.m3u8"))
            }
        }
        val providers = directory(adapter)
        val catalog = Catalog(this, noNetwork, listOf(source, other), providers, Clock())
        catalog.refresh().await()
        assertEquals(2, catalog.items().map { it.id }.distinct().size)
        val resolver = StreamResolver(this, noNetwork, providers)
        val resolved = catalog.items().map { resolver.resolve(it).first().url }
        assertEquals(2, resolved.distinct().size)
        assertEquals(2, resolutions)
    }

    @Test fun reconfigurationRejectsLateDiscoveryFromOldGeneration() = runTest {
        val entered = CompletableDeferred<Unit>()
        val providers = directory(provider {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(100) }
            ProviderResult(listOf(row))
        })
        val catalog = Catalog(this, noNetwork, listOf(source), providers, Clock())
        val old = catalog.refresh()
        entered.await()
        catalog.configure(emptyList())
        advanceUntilIdle()
        assertTrue(old.isCancelled)
        assertTrue(catalog.items().isEmpty())
        assertTrue(catalog.status.value.isEmpty())
    }

    @Test fun scoreFeedCanBeReplacedWithoutChangingDomainOrProtocolAdapters() = runTest {
        var calls = 0
        val clock = Clock()
        val feed = ScoreFeed { league, _, observed ->
            calls++
            listOf(ScoreFixture("fixture", league, Team("Home"), Team("Away"), row.starts, MatchState.LIVE, "1", "0", "Live", observed))
        }
        val scores = FixtureService(this, feed, clock)
        val entry = MatchNormalizer.aggregate(listOf(row), emptyList(), PresentationConfig(), clock.wall).single()
        scores.request(entry, PresentationConfig())
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(MatchState.LIVE, scores.fixtures().single().state)
        scores.request(entry, PresentationConfig())
        advanceUntilIdle()
        assertEquals(1, calls)
        scores.reset()
        advanceUntilIdle()
        assertTrue(scores.fixtures().isEmpty())
        assertEquals(1, calls)
    }

    @Test fun scoreFailureIsSafeOptionalAndDoesNotBlockCatalogOrResolution() = runTest {
        val scores = FixtureService(this, ScoreFeed { _, _, _ -> error("secret-signature-must-not-escape") }, Clock())
        val entry = MatchNormalizer.aggregate(listOf(row), emptyList(), PresentationConfig(), 100_000).single()
        scores.request(entry, PresentationConfig())
        advanceUntilIdle()
        assertTrue(scores.fixtures().isEmpty())
        assertFalse(scores.lastError.contains("secret-signature"))
        val providers = directory(provider())
        val catalog = Catalog(this, noNetwork, listOf(source), providers, Clock())
        catalog.refresh().await()
        assertTrue(StreamResolver(this, noNetwork, providers).resolve(catalog.items().single()).first().url.isNotBlank())
    }

    @Test fun workbenchUsesInjectedAdapterAndMediaProbeAndDoesNotClaimNativePlayback() = runTest {
        val providers = directory(provider())
        val media = object : MediaProbe {
            override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean) = listOf(SegmentResult(188, "MPEG-TS", "fixture-signature"))
        }
        val workbench = ProviderWorkbench(this, noNetwork, StreamResolver(this, noNetwork, providers), providers, media, Clock())
        val report = workbench.test(source)
        assertEquals(ProbePhase.COMPLETE, report.phase)
        val choice = report.listings.single().choices.single()
        assertEquals(ProbeStage.SEGMENTS_FETCHED, choice.stage)
        assertEquals(PlaybackStatus.NOT_CONFIRMED, choice.playback)
        workbench.confirmPlayback(source.id, choice.id, true)
        assertEquals(PlaybackStatus.CONFIRMED_BY_USER, workbench.reports.value.getValue(source.id).listings.single().choices.single().playback)
        workbench.reset()
        assertTrue(workbench.entries().isEmpty())
        assertTrue(workbench.reports.value.isEmpty())
        assertTrue(workbench.candidates.value.isEmpty())
    }

    @Test fun resolutionDoesNotMergeDifferentProviderCredentialsForTheSameMediaUrl() = runTest {
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(row))
            override suspend fun resolve(item: Listing, net: Upstream) = listOf(
                StreamChoice("One", "https://media.example/live.m3u8", SourceHeaders(extra = mapOf("usertoken" to "profile-one"))),
                StreamChoice("Two", "https://media.example/live.m3u8", SourceHeaders(extra = mapOf("usertoken" to "profile-two"))))
        }
        val choices = StreamResolver(this, noNetwork, directory(adapter), Clock()).resolve(row)
        assertEquals(2, choices.count { it.url.isNotBlank() })
    }

    @Test fun obsoleteWorkbenchProbeCannotPublishIntoNewConfiguration() = runTest {
        val entered = CompletableDeferred<Unit>()
        val providers = directory(provider())
        val media = object : MediaProbe {
            override suspend fun probe(choice: StreamChoice, verifyMedia: Boolean): List<SegmentResult> {
                entered.complete(Unit)
                withContext(NonCancellable) { delay(100) }
                return listOf(SegmentResult(188, "MPEG-TS", "obsolete"))
            }
        }
        val workbench = ProviderWorkbench(this, noNetwork, StreamResolver(this, noNetwork, providers), providers, media, Clock())
        val old = workbench.start(source)
        entered.await()
        workbench.reset()
        advanceUntilIdle()
        assertTrue(old.isCancelled)
        assertTrue(workbench.reports.value.isEmpty())
        assertTrue(workbench.entries().isEmpty())
    }
}
