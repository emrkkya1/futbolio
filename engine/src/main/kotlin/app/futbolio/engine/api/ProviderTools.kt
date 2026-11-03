package app.futbolio.engine.api

import app.futbolio.application.api.Failures
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.HttpUpstream
import app.futbolio.application.api.ProviderTestReport
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.engine.adapters.providers.ProviderRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ProviderCheck(val id: String, val name: String, val count: Int = 0, val warning: String = "", val error: String = "")

/** Headless diagnostics use the same runtime composition, not host-owned adapters. */
object ProviderTools {
    suspend fun checkSources(sources: List<SourceConfig> = Configurations.sources()): List<ProviderCheck> {
        Configurations.validateSources(sources)
        return HttpUpstream().use { net ->
            val discovery = DomainRecovery(net, ProviderRegistry())
            sources.map { source ->
                try {
                    val result = withTimeout(35_000) { discovery.discover(source) }
                    ProviderCheck(source.id, source.name, result.listings.size, result.warning)
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    ProviderCheck(source.id, source.name, error = "Source timed out")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { ProviderCheck(source.id, source.name, error = Failures.describe(failure)) }
            }
        }
    }

    suspend fun probe(sources: List<SourceConfig>, listingId: String? = null): List<ProviderTestReport> = coroutineScope {
        Configurations.validateSources(sources)
        HttpUpstream().use { net ->
            val providers = ProviderRegistry()
            val resolver = StreamResolver(this, net, providers)
            val permits = Semaphore(2)
            try {
                sources.map { source -> async {
                    permits.withPermit { ProviderWorkbench(this@coroutineScope, net, resolver, providers, SegmentProbe(net)).test(source, listingId) }
                } }.awaitAll()
            } finally { resolver.trim() }
        }
    }
}
