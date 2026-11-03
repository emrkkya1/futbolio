package app.futbolio.application.catalog

import app.futbolio.application.api.*
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Incrementally published provider snapshots; never wait for the slowest provider. */
class Catalog(
    private val scope: CoroutineScope,
    net: Upstream,
    sources: List<SourceConfig>,
    providers: ProviderDirectory,
    private val time: TimeSource = TimeSource.System,
    private val limits: ResourceLimits = ResourceLimits(),
) {
    private val recovery = DomainRecovery(net, providers)
    private val validator = ConfigurationValidator(providers, limits)
    private data class State(val source: SourceConfig, var rows: List<Listing> = emptyList(), var status: ProviderStatus, var successfulAt: Long? = null)
    private val states = linkedMapOf<String, State>()
    private val mutableStatus = MutableStateFlow<List<ProviderStatus>>(emptyList())
    val status = mutableStatus.asStateFlow()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private var pending: Deferred<Unit>? = null
    private var lastRefresh: Long? = null
    private var generation = 0L

    init { configure(sources) }

    @Synchronized fun configure(sources: List<SourceConfig>) {
        validator.validateSources(sources)
        generation++
        pending?.cancel()
        pending = null
        lastRefresh = null
        recovery.reset()
        states.clear()
        sources.forEach { states[it.id] = State(it, status = ProviderStatus(it.id, it.name, it.enabled)) }
        publish()
    }

    @Synchronized fun refresh(force: Boolean = false): Deferred<Unit> {
        pending?.takeIf { !it.isCompleted }?.let { return it }
        if (!force && lastRefresh?.let { time.monotonicMillis() - it < limits.catalogRefreshMillis } == true) return CompletableDeferred(Unit)
        val epoch = generation
        val batch = states.values.filter { it.source.enabled }
        batch.forEach { it.status = it.status.copy(loading = true) }
        publish()
        lastRefresh = time.monotonicMillis()
        return scope.async {
            batch.map { state -> async {
                var result: ProviderResult? = null
                var error = ""
                try { result = withTimeout(35_000) { recovery.discover(state.source) } }
                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); error = "Source timed out" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = Failures.describe(failure) }
                synchronized(this@Catalog) {
                    if (epoch != generation) return@synchronized
                    val now = time.wallMillis()
                    if (result != null) {
                        state.rows = result.listings.distinctBy { it.id }.take(limits.listingsPerSource)
                            .map { if (it.starts == 0L && !it.channel) it.copy(observedAt = now) else it }
                        state.successfulAt = time.monotonicMillis()
                    } else if (!fresh(state)) state.rows = emptyList()
                    state.status = state.status.copy(loading = false, ok = result != null, count = state.rows.size,
                        updated = if (result != null) now else state.status.updated, checked = now,
                        error = error, warning = result?.warning.orEmpty(), activeUrl = result?.activeUrl ?: state.status.activeUrl)
                    publish()
                }
            } }.awaitAll()
            Unit
        }.also { pending = it }
    }

    suspend fun snapshot(): List<Listing> {
        refresh()
        if (items().isEmpty()) withTimeoutOrNull(2_000) {
            status.first { values -> items().isNotEmpty() || values.none { it.loading } }
        }
        return items()
    }

    private fun fresh(state: State) = state.successfulAt?.let { time.monotonicMillis() - it < limits.staleCatalogMillis } == true

    @Synchronized fun items(): List<Listing> = states.values.filter { it.source.enabled && fresh(it) }
        .flatMap { it.rows }

    @Synchronized fun trim() {
        states.values.forEach { state -> if (!fresh(state)) state.rows = emptyList() }
        publish()
    }

    @Synchronized fun configuredSources() = states.values.map { it.source }
    private fun publish() {
        mutableStatus.value = states.values.map { it.status }
        mutableChanges.value++
    }
}
