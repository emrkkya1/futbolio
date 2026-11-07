package app.futbolio.application.resolution

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.EventSink
import app.futbolio.application.ports.TimeSource
import app.futbolio.application.ports.StreamChoice
import app.futbolio.application.ports.ConnectionStatus
import app.futbolio.application.ports.isHls
import app.futbolio.domain.catalog.CatalogEntry
import app.futbolio.domain.catalog.LiveMatchPriority
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MatchPrefetchTiming(
    val matchId: String,
    val firstPreparedMillis: Long? = null,
    val firstRatedMillis: Long? = null,
    val firstGoodMillis: Long? = null,
    val measuredProviders: Set<String> = emptySet(),
)

data class PrefetchBatchReport(val elapsedMillis: Long = 0, val preparedListings: Int = 0, val matches: List<MatchPrefetchTiming> = emptyList())

/** Speculates only during a short app-start/catalog-browsing window, never indefinitely. */
class LiveStreamPrefetcher(
    private val scope: CoroutineScope,
    private val resolver: StreamResolver,
    private val snapshot: suspend () -> List<CatalogEntry>,
    private val preferences: () -> PresentationConfig,
    private val time: TimeSource = TimeSource.System,
    private val limits: ResourceLimits = ResourceLimits(),
    private val events: EventSink = EventSink {},
) {
    private var job: Job? = null
    private var preparationJob: Job? = null
    private var batch: Job? = null
    private var interestedUntil = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val prepareWake = Channel<Unit>(Channel.CONFLATED)
    private var requested: CatalogEntry? = null
    private var demandPending = false
    private val refreshes = mutableMapOf<String, Job>()
    @Volatile var lastReport = PrefetchBatchReport()
        private set

    @Synchronized fun browsing() {
        interestedUntil = time.monotonicMillis() + limits.prefetchInterestMillis
        wake.trySend(Unit)
        prepareWake.trySend(Unit)
    }

    /** Selection promotes preparation, but never joins a bandwidth check on HTTP's path. */
    @Synchronized fun selected(entry: CatalogEntry) {
        requested = entry
        demandPending = entry.listings.any { !resolver.hasFresh(it) }
        browsing()
    }

    /** New provider snapshots can become useful before the next maintenance interval. */
    fun changed() { wake.trySend(Unit); prepareWake.trySend(Unit) }

    @Synchronized fun start() {
        if (job?.isActive == true) return
        browsing()
        preparationJob = scope.launch {
            prepareWake.tryReceive()
            while (isActive) {
                try { refreshLinksOnce() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { events.event("Live prefetch: link refresh unavailable") }
                withTimeoutOrNull(limits.prefetchIntervalMillis) { prepareWake.receive() }
            }
        }
        job = scope.launch {
            wake.tryReceive()
            while (isActive) {
                try { warmOnce() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { events.event("Live prefetch: preparation unavailable") }
                withTimeoutOrNull(limits.prefetchIntervalMillis) { wake.receive() }
            }
        }
    }

    private fun allowed() = synchronized(this) { time.monotonicMillis() < interestedUntil } && resolver.prefetchAllowed()

    private suspend fun candidates(): List<CatalogEntry> {
        val demand = synchronized(this) { requested }
        val live = LiveMatchPriority.select(snapshot(), preferences(), time.wallMillis(), limits.prefetchMatches)
        return (listOfNotNull(demand) + live).distinctBy { it.id }.take(limits.prefetchMatches)
    }

    /** Independent maintenance: a slow sampling batch cannot let all prepared URLs expire. */
    suspend fun refreshLinksOnce() {
        if (synchronized(this) { time.monotonicMillis() >= interestedUntil }) return
        val revision = resolver.revision()
        val selected = candidates()
        val coldDemand = synchronized(this) {
            if (requested?.listings?.all(resolver::hasFresh) == true) demandPending = false
            requested?.takeIf { demandPending }
        }
        if (!allowed() && coldDemand == null) return
        coldDemand?.let { resolver.prioritize(it.listings, revision) }
        // Dispatch a bounded set and wake again as each completes, rather than
        // awaiting an entire extraction batch. A stalled provider cannot prevent
        // other ready links from being refreshed on the next maintenance tick.
        for ((listing, foreground) in selected.flatMap { entry -> entry.listings.map { it to (entry.id == coldDemand?.id) } }
            .distinctBy { it.first.id }.take(limits.prefetchListingsPerBatch)) {
            if (!resolver.needsRefresh(listing) || (!foreground && !allowed())) continue
            synchronized(this) {
                if (time.monotonicMillis() >= interestedUntil || revision != resolver.revision() ||
                    refreshes.size >= limits.activeResolutions || listing.id in refreshes) return@synchronized
                val task = scope.launch(start = CoroutineStart.LAZY) {
                    try { resolver.refreshPrepared(listing, revision, background = !foreground) }
                    catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (_: Exception) { events.event("Live prefetch: source refresh unavailable") }
                }
                refreshes[listing.id] = task
                task.invokeOnCompletion {
                    synchronized(this@LiveStreamPrefetcher) { if (refreshes[listing.id] === task) refreshes.remove(listing.id) }
                    prepareWake.trySend(Unit)
                }
                task.start()
            }
        }
    }

    /** Exposed for deterministic scheduling tests; no unbounded catalog or provider fan-out. */
    suspend fun warmOnce(): List<String> = coroutineScope {
        if (!allowed()) return@coroutineScope emptyList()
        val revision = resolver.revision()
        val selected = candidates()
        if (!allowed() || revision != resolver.revision()) return@coroutineScope emptyList()
        // Provider slots are held only during extraction. Measurement never blocks the next
        // preparation, and the highest-interest match enters both queues first.
        val listings = selected.flatMap { it.listings }.distinctBy { it.id }.take(limits.prefetchListingsPerBatch)
        if (listings.isEmpty()) return@coroutineScope selected.map { it.id }
        val started = time.monotonicMillis()
        val progress = Any()
        val timings = selected.associate { it.id to MatchPrefetchTiming(it.id) }.toMutableMap()
        var preparedCount = 0
        fun prepared(entry: CatalogEntry, choices: List<StreamChoice>) = synchronized(progress) {
            preparedCount++
            if (choices.any { it.url.isNotBlank() }) {
                val current = timings.getValue(entry.id)
                if (current.firstPreparedMillis == null) timings[entry.id] = current.copy(firstPreparedMillis = time.monotonicMillis() - started)
            }
        }
        fun rated(entry: CatalogEntry, choices: List<StreamChoice>) = synchronized(progress) {
            val current = timings.getValue(entry.id)
            val elapsed = time.monotonicMillis() - started
            val hasGrade = choices.any { it.assessment.status in listOf(ConnectionStatus.GOOD, ConnectionStatus.MEDIUM, ConnectionStatus.SLOW) }
            val hasGood = choices.any { it.assessment.status == ConnectionStatus.GOOD }
            if (current.firstRatedMillis == null && hasGrade) {
                events.event("Live prefetch: ${entry.id} first rated source in $elapsed ms")
            }
            if (current.firstGoodMillis == null && hasGood) events.event("Live prefetch: ${entry.id} first GOOD source in $elapsed ms")
            timings[entry.id] = current.copy(
                firstRatedMillis = current.firstRatedMillis ?: elapsed.takeIf { hasGrade },
                firstGoodMillis = current.firstGoodMillis ?: elapsed.takeIf { hasGood },
            )
        }
        val work = async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(limits.prefetchBatchMillis) {
                val owners = selected.flatMap { entry -> entry.listings.map { it.id to entry } }.toMap()
                val preparationSlots = Semaphore(limits.activeResolutions)
                val preparations = listings.associateWith { listing -> async {
                    preparationSlots.withPermit {
                        if (!allowed()) return@withPermit emptyList<StreamChoice>()
                        val choices = try { resolver.prepare(listing, revision) }
                        catch (_: CancellationException) { currentCoroutineContext().ensureActive(); emptyList() }
                        prepared(owners.getValue(listing.id), choices)
                        choices
                    }
                } }
                // Retain existing fresh provider slots across maintenance passes.
                // Completion order must not gradually turn a three-provider cap
                // into benchmarks for every provider on the next tick.
                val measured = selected.associate { entry -> entry.id to linkedMapOf<String, Listing>().apply {
                    entry.listings.filter { resolver.hasAssessment(it) }.forEach { listing ->
                        if (size < limits.measuredProvidersPerMatch) putIfAbsent(listing.source.kind, listing)
                    }
                } }
                // Give each likely match a useful first pass before spending time on second
                // options. Consume whichever provider becomes ready first, not registry order.
                for (entry in selected) {
                    if (!allowed()) break
                    val remaining = preparations.filterKeys { listing -> entry.listings.any { it.id == listing.id } }.toMutableMap()
                    var active: Listing? = null
                    try {
                        withTimeoutOrNull(limits.prefetchInitialMatchMillis) {
                            val providers = measured.getValue(entry.id)
                            while (remaining.isNotEmpty() && providers.size < limits.measuredProvidersPerMatch && allowed()) {
                                val (listing, choices) = select<Pair<Listing, List<StreamChoice>>> {
                                    remaining.forEach { (listing, deferred) -> deferred.onAwait { listing to it } }
                                }
                                remaining.remove(listing)
                                if (listing.source.kind in providers || choices.none { it.url.isNotBlank() && isHls(it) }) continue
                                providers[listing.source.kind] = listing
                                synchronized(progress) { timings[entry.id] = timings.getValue(entry.id).copy(measuredProviders = providers.keys.toSet()) }
                                active = listing
                                val result = resolver.measurePrepared(listing, revision, choices = 1)
                                rated(entry, result)
                                // If the first option is marginal, try one prepared alternative
                                // within this match's same short budget before moving away.
                                val needsBetter = synchronized(progress) { timings.getValue(entry.id).firstGoodMillis == null }
                                if (needsBetter && limits.prefetchChoicesPerListing > 1 &&
                                    result.any { it.assessment.status in listOf(ConnectionStatus.MEDIUM, ConnectionStatus.SLOW) } &&
                                    choices.count { it.url.isNotBlank() && isHls(it) } > 1) {
                                    rated(entry, resolver.measurePrepared(listing, revision, choices = 2))
                                }
                                active = null
                            }
                        }
                    } finally { active?.let(resolver::cancelPrefetch) }
                }
                // Deepening reuses the first result; it does not download the first option again.
                for (entry in selected) {
                    for (listing in measured.getValue(entry.id).values) {
                        if (!allowed()) break
                        if (resolver.hasFresh(listing)) rated(entry, resolver.measurePrepared(listing, revision, limits.prefetchChoicesPerListing))
                    }
                }
                preparations.values.awaitAll()
            }
        }
        synchronized(this@LiveStreamPrefetcher) { batch = work; work.start() }
        try { work.await() }
        catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        finally {
            synchronized(this@LiveStreamPrefetcher) { if (batch === work) batch = null }
            synchronized(progress) { lastReport = PrefetchBatchReport(time.monotonicMillis() - started, preparedCount, selected.map { timings.getValue(it.id) }) }
        }
        events.event("Live prefetch: ${selected.size} priority matches, ${listings.size} provider listings considered")
        selected.map { it.id }
    }

    /** Memory pressure/settings changes must not immediately restart canceled speculative work. */
    fun reset() {
        val obsolete = synchronized(this) {
            interestedUntil = 0; requested = null; demandPending = false
            (refreshes.values.toList() + listOfNotNull(batch)).also { refreshes.clear() }
        }
        obsolete.forEach { it.cancel() }
        resolver.cancelPrefetches()
    }

    fun stop() {
        val obsolete = synchronized(this) { job.also { job = null } }
        obsolete?.cancel()
        preparationJob?.cancel(); preparationJob = null
        reset()
    }
}
