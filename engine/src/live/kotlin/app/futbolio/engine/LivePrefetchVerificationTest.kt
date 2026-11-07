package app.futbolio.engine

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.LiveStreamPrefetcher
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.LiveMatchPriority
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.adapters.probes.HlsStreamAssessor
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.api.Configurations
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Explicit online warm-up/cache experiment; not a proof of freeze-free native playback. */
class LivePrefetchVerificationTest {
    @Test fun priorityMatchesCanReturnFreshMeasurementsWithoutNewNetworkChecks() = runBlocking<Unit> {
        HttpUpstream().use { upstream ->
            val requests = AtomicInteger()
            val net = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                    requests.incrementAndGet()
                    return upstream.open(url, headers, range)
                }
                override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders): UpstreamResponse {
                    requests.incrementAndGet()
                    return upstream.postForm(url, fields, headers)
                }
            }
            val registry = ProviderRegistry()
            val config = PresentationConfig()
            val now = System.currentTimeMillis()
            val rows = Configurations.sources().map { source -> async {
                try { withTimeout(40_000) { DomainRecovery(net, registry).discover(source).listings } }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); emptyList() }
            } }.awaitAll().flatten().map { if (it.observedAt == 0L) it.copy(observedAt = now) else it }
            val entries = MatchNormalizer.aggregate(rows, emptyList(), config, now)
            val selected = LiveMatchPriority.select(entries, config, now)
            assumeTrue("No curated Live Now candidates available", selected.isNotEmpty())
            selected.forEach { println("PREFETCH candidate=${it.title}; score=${LiveMatchPriority.score(it, config)}; providers=${it.listings.size}") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val limits = ResourceLimits()
            val resolver = StreamResolver(scope, net, registry, limits = limits, assessor = HlsStreamAssessor(net))
            val warmer = LiveStreamPrefetcher(scope, resolver, { entries }, { config }, limits = limits)
            try {
                warmer.browsing()
                val start = System.nanoTime()
                warmer.warmOnce()
                scope.coroutineContext[Job]?.children?.toList()?.joinAll()
                println("PREFETCH batchMillis=${(System.nanoTime() - start) / 1_000_000}")
                warmer.lastReport.matches.forEach { timing ->
                    println("PREFETCH readiness=${selected.firstOrNull { it.id == timing.matchId }?.title}; firstLinksMillis=${timing.firstPreparedMillis}; firstGradeMillis=${timing.firstRatedMillis}; firstGoodMillis=${timing.firstGoodMillis}; measuredProviders=${timing.measuredProviders}")
                    assertTrue(timing.measuredProviders.size <= limits.measuredProvidersPerMatch)
                }
                val warmed = selected.flatMap { it.listings }.distinctBy { it.id }.filter(resolver::hasFresh)
                println("PREFETCH freshListings=${warmed.size}; fullyWarmMatches=${selected.count { entry -> entry.listings.all(resolver::hasFresh) }}")
                assumeTrue("Warm-up had no fresh provider choices before its deadline", warmed.isNotEmpty())
                for (listing in warmed) {
                    val before = requests.get()
                    val selectedAt = System.nanoTime()
                    val choices = resolver.resolve(listing)
                    val millis = (System.nanoTime() - selectedAt) / 1_000_000
                    val newRequests = requests.get() - before
                    println("PREFETCH cached=${listing.title}; provider=${listing.source.name}; selectMillis=$millis; newRequests=$newRequests; levels=${choices.filter { it.url.isNotBlank() }.map { it.assessment.status.name }}")
                    assertEquals(0, newRequests, "A fresh warm-cache hit must not probe again")
                }
                println("PREFETCH nativePlaybackVerified=false; partial warming does not promise an instant complete match response")
            } finally { warmer.stop(); resolver.trim(); scope.cancel() }
        }
    }
}
