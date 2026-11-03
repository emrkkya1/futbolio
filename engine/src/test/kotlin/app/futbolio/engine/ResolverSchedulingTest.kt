package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry

import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.engine.api.Configurations

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverSchedulingTest {
    private class Clock : TimeSource {
        var wall = 1_000_000L
        var ticks = 0L
        override fun wallMillis() = wall
        override fun monotonicMillis() = ticks
    }

    @Test fun cacheExpiryUsesMonotonicTimeRatherThanWallClock() = runTest {
        val clock = Clock()
        var requests = 0
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests++
                return UpstreamResponse(200, url, emptyMap(), "#EXTM3U\npart.ts".byteInputStream())
            }
        }
        val source = Configurations.sources().first { it.kind == "futbolvivo" }
        val item = Listing(source, "Home vs Away", "Football", "https://fixture.example/live")
        val resolver = StreamResolver(this, net, ProviderRegistry(), time = clock)
        resolver.resolve(item)
        clock.wall += 60 * 60_000
        resolver.resolve(item)
        assertEquals(1, requests)
        clock.wall -= 24 * 60 * 60_000
        clock.ticks += 20_001
        resolver.resolve(item)
        assertEquals(2, requests)
    }

    @Test fun sameIdentitySharesOneResolutionAndTimeoutUsesVirtualTime() = runTest {
        var requests = 0
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests++
                awaitCancellation()
            }
        }
        val source = Configurations.sources().first { it.kind == "futbolvivo" }
        val item = Listing(source, "Home vs Away", "Football", "https://fixture.example/live")
        val resolver = StreamResolver(this, net, ProviderRegistry(), limits = ResourceLimits(resolutionTimeoutMillis = 100))
        val first = async { resolver.resolve(item) }
        val second = async { resolver.resolve(item) }
        assertEquals(item.page, first.await().single().externalUrl)
        assertEquals(first.await(), second.await())
        assertEquals(1, requests)
        assertEquals(100, testScheduler.currentTime)
    }
}
