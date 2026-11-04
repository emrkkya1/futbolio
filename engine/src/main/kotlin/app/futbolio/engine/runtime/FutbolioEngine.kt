package app.futbolio.engine.runtime

import app.futbolio.application.api.*
import app.futbolio.application.catalog.*
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.application.fixtures.FixtureService
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.HttpUpstream
import app.futbolio.engine.adapters.diagnostics.Diagnostics
import app.futbolio.engine.adapters.posters.PosterStore
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.relay.*
import app.futbolio.engine.adapters.scores.espn.EspnScoreFeed
import app.futbolio.engine.adapters.stremio.server.StremioServer
import app.futbolio.engine.adapters.stremio.wire.StremioWire
import app.futbolio.engine.api.EngineControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TransportOwnership { OWNED, BORROWED }

/** Owned runtime graph and lifecycle only. Hosts receive EngineControl, never this implementation. */
internal class FutbolioEngine(
    override val host: HostConfig,
    sources: List<SourceConfig>,
    poster: PosterRenderer,
    private val net: Upstream = HttpUpstream(),
    activity: MediaActivity = MediaActivity.None,
    presentation: PresentationConfig = PresentationConfig(),
    private val limits: ResourceLimits = ResourceLimits(),
    private val time: TimeSource = TimeSource.System,
    scoreFeed: ScoreFeed = EspnScoreFeed(net),
    private val providers: ProviderDirectory = ProviderRegistry(),
    private val ownership: TransportOwnership = TransportOwnership.OWNED,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EngineControl {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lifecycle = Mutex()
    private val mutations = Any()
    private val disposed = AtomicBoolean()
    private val stopped = CompletableDeferred<Unit>()
    private val mutablePhase = MutableStateFlow(RuntimePhase.STOPPED)
    override val phase = mutablePhase.asStateFlow()
    internal val diagnostics = Diagnostics(time, limits)
    internal val catalog = Catalog(scope, net, sources, providers, time, limits)
    internal val resolver = StreamResolver(scope, net, providers, time, limits, diagnostics)
    internal val tokens = RelayTokens(host.base, limits, time::monotonicMillis)
    internal val workbench = ProviderWorkbench(scope, net, resolver, providers, SegmentProbe(net), time)
    private val fixtures = FixtureService(scope, scoreFeed, time)
    private val entries = EntryCatalog(catalog, fixtures, presentation, scope, time, events = diagnostics)
    private val posters = PosterStore(entries, poster, time, limits)
    private val relay = MediaRelay(net, tokens, diagnostics, activity, limits, io)
    private val server = StremioServer(host, this, catalog, entries, resolver, workbench, fixtures, posters, relay, tokens, diagnostics, limits, time)

    override val providerStatuses get() = catalog.status
    override val providerReports get() = workbench.reports
    override val probeCandidates get() = workbench.candidates
    private val baseSnapshot = combine(phase, providerStatuses, providerReports, probeCandidates,
        flow { while (currentCoroutineContext().isActive) { emit(diagnosticSnapshot()); delay(2000) } }) { phase, providers, reports, candidates, diagnostic ->
        RuntimeSnapshot(phase, providers, reports, candidates, diagnostic)
    }
    override val snapshot = combine(baseSnapshot, workbench.batchState) { snapshot, batch -> snapshot.copy(testBatch = batch) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), RuntimeSnapshot())

    override suspend fun start() = lifecycle.withLock {
        check(!disposed.get()) { "A closed engine cannot be restarted; create a new runtime" }
        check(mutablePhase.value == RuntimePhase.STOPPED) { "Engine already running" }
        mutablePhase.value = RuntimePhase.STARTING
        try {
            server.start()
            check(!disposed.get()) { "Engine closed during startup" }
            mutablePhase.value = RuntimePhase.RUNNING
            diagnostics.event("${if (host.desktop) "Desktop" else "On-device"} service started")
            catalog.refresh()
            Unit
        } catch (failure: Throwable) {
            server.stop()
            mutablePhase.value = if (disposed.get()) RuntimePhase.STOPPING else RuntimePhase.STOPPED
            throw failure
        }
    }

    override fun configure(sources: List<SourceConfig>, presentation: PresentationConfig) = synchronized(mutations) {
        check(!disposed.get()) { "Engine is closed" }
        ConfigurationValidator(providers, limits).validateSources(sources)
        presentation.validate()
        workbench.reset()
        catalog.configure(sources)
        entries.configure(presentation)
        resolver.trim()
        posters.clear()
        catalog.refresh(true)
        diagnostics.event("Provider settings updated")
    }

    internal fun configure(sources: List<SourceConfig>) = configure(sources, entries.presentation())
    override fun refreshProviders() = synchronized(mutations) { check(!disposed.get()); catalog.refresh(true); Unit }
    override fun testProvider(sourceId: String, listingId: String?) = synchronized(mutations) {
        check(!disposed.get())
        val source = catalog.configuredSources().firstOrNull { it.id == sourceId } ?: error("Unknown configured provider")
        workbench.start(source, listingId)
        Unit
    }
    override fun testAllProviders() = synchronized(mutations) {
        check(!disposed.get())
        workbench.startAll(catalog.configuredSources())
        Unit
    }
    override fun confirmPlayback(sourceId: String, choiceId: String, works: Boolean) = workbench.confirmPlayback(sourceId, choiceId, works)
    override fun diagnosticSnapshot() = diagnostics.snapshot()
    override fun trim() { catalog.trim(); resolver.trim(); tokens.trim(); posters.clear() }
    internal fun manifest() = StremioWire(host).manifest()

    override suspend fun shutdown() {
        if (!disposed.compareAndSet(false, true)) { stopped.await(); return }
        withContext(NonCancellable + io) {
            mutablePhase.value = RuntimePhase.STOPPING
            try {
                lifecycle.withLock {
                    try { server.stop() } finally {
                        synchronized(mutations) {
                            workbench.reset(); resolver.trim(); fixtures.reset(); entries.clear(); posters.clear(); tokens.clear()
                            scope.cancel()
                        }
                        if (ownership == TransportOwnership.OWNED) net.close()
                        withTimeoutOrNull(2000) { scope.coroutineContext[Job]?.join() }
                    }
                }
            } finally { mutablePhase.value = RuntimePhase.DISPOSED; stopped.complete(Unit) }
        }
    }

    /** Compatibility for synchronous owners; presentation uses suspending shutdown off the UI thread. */
    override fun close() { runBlocking(io) { shutdown() } }
}
