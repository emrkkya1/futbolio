package app.futbolio.application.resolution

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.CatalogEntry
import app.futbolio.domain.catalog.LiveMatchPriority
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MatchPrefetchTiming(
    val matchId: String,
    val firstPreparedMillis: Long? = null,
    val firstRatedMillis: Long? = null,
    val firstGoodMillis: Long? = null,
    val measuredProviders: Set<String> = emptySet(),
    val minimumReadyMillis: Long? = null,
)

data class PrefetchBatchReport(val elapsedMillis: Long = 0, val preparedListings: Int = 0, val matches: List<MatchPrefetchTiming> = emptyList())
enum class PreparationPhase { IDLE, DISCOVERY, PRIORITY_MINIMUM, ENRICHMENT, EXPANSION, FOREGROUND, PAUSED }

/** One ordered, preemptible pipeline. Minimum sets never compete with enrichment. */
class LiveStreamPrefetcher(
    private val scope: CoroutineScope,
    private val resolver: StreamResolver,
    private val snapshot: suspend () -> List<CatalogEntry>,
    private val preferences: () -> PresentationConfig,
    private val time: TimeSource = TimeSource.System,
    private val limits: ResourceLimits = ResourceLimits(),
    private val events: EventSink = EventSink {},
    private val initialize: suspend () -> Unit = {},
) {
    private data class Plan(val revision: Long, val listings: List<List<String>>)
    private var job: Job? = null
    private var batch: Job? = null
    private var foreground: Job? = null
    private var requested: CatalogEntry? = null
    private var enabled = false
    private var initializedRevision: Long? = null
    private var plan: Plan? = null
    private var priorityListings = emptyList<Listing>()
    private var maintenanceCursor = 0
    private val minimumDone = mutableSetOf<String>()
    private val enrichmentDone = mutableSetOf<String>()
    private val entriesEnriched = mutableSetOf<String>()
    private val providerSlots = mutableMapOf<String, LinkedHashMap<String, Listing>>()
    private val expanded = mutableMapOf<String, Set<String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile var phase = PreparationPhase.IDLE
        private set
    @Volatile var lastReport = PrefetchBatchReport()
        private set

    @Synchronized fun browsing() { enabled = true; wake.trySend(Unit) }
    fun changed() { wake.trySend(Unit) }

    /** A cold user selection cancels unrelated work, not completed cache entries. */
    fun selected(entry: CatalogEntry) {
        val revision = resolver.revision()
        val obsolete: Job?
        synchronized(this) {
            enabled = true
            if (requested?.id == entry.id && foreground?.isActive == true) return
            requested = entry
            resolver.protectPrepared(entry.listings + priorityListings)
            if (resolver.preparedSnapshot(entry.listings, revision) != null) { wake.trySend(Unit); return }
            resolver.preemptFor(entry.listings, revision)
            batch?.cancel()
            obsolete = foreground
            phase = PreparationPhase.FOREGROUND
            val task = scope.launch(start = CoroutineStart.LAZY) {
                try { resolver.awaitPreparedSnapshot(entry.listings, revision) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { events.event("Selected match: minimum preparation unavailable") }
            }
            foreground = task
            task.invokeOnCompletion {
                synchronized(this@LiveStreamPrefetcher) {
                    if (foreground === task) { foreground = null; phase = PreparationPhase.IDLE }
                }
                wake.trySend(Unit)
            }
            task.start()
        }
        obsolete?.cancel()
    }

    @Synchronized fun start() {
        if (job?.isActive == true) return
        enabled = true
        job = scope.launch {
            while (isActive) {
                try { warmOnce() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { events.event("Stream preparation: cycle unavailable") }
                withTimeoutOrNull(limits.prefetchIntervalMillis) { wake.receive() }
            }
        }
    }

    private fun allowed() = synchronized(this) { enabled && foreground?.isActive != true } && resolver.prefetchAllowed()
    private suspend fun checkAllowed(revision: Long) {
        currentCoroutineContext().ensureActive()
        if (!allowed() || revision != resolver.revision()) throw CancellationException("Background preparation yielded")
    }
    private fun setPhase(value: PreparationPhase) = synchronized(this) {
        if (phase != value) { phase = value; events.event("Stream preparation: ${value.name.lowercase()}") }
    }

    /** One finite pass; timeouts/failures count as attempts, not an endless freshness barrier. */
    suspend fun warmOnce(): List<String> = coroutineScope {
        if (!allowed()) return@coroutineScope emptyList()
        val revision = resolver.revision()
        val started = time.monotonicMillis()
        val progress = Any()
        var selected = emptyList<CatalogEntry>()
        val timings = linkedMapOf<String, MatchPrefetchTiming>()
        var preparedCount = 0
        fun prepared(entry: CatalogEntry, choices: List<StreamChoice>) = synchronized(progress) {
            preparedCount++
            val current = timings[entry.id] ?: return@synchronized
            if (current.firstPreparedMillis == null && choices.any { it.url.isNotBlank() })
                timings[entry.id] = current.copy(firstPreparedMillis = time.monotonicMillis() - started)
        }
        fun rated(entry: CatalogEntry, choices: List<StreamChoice>) = synchronized(progress) {
            val current = timings.getValue(entry.id)
            val elapsed = time.monotonicMillis() - started
            timings[entry.id] = current.copy(
                firstRatedMillis = current.firstRatedMillis ?: elapsed.takeIf { choices.any { it.assessment.status in setOf(ConnectionStatus.GOOD, ConnectionStatus.MEDIUM, ConnectionStatus.SLOW) } },
                firstGoodMillis = current.firstGoodMillis ?: elapsed.takeIf { choices.any { it.assessment.status == ConnectionStatus.GOOD } },
            )
        }
        suspend fun minimum(entry: CatalogEntry, refresh: Boolean = false, remember: Boolean = false) = coroutineScope {
            val slots = Semaphore(limits.activeResolutions)
            entry.listings.distinctBy { it.id }.map { listing -> async {
                slots.withPermit {
                    checkAllowed(revision)
                    if (refresh && !resolver.needsRefresh(listing)) return@withPermit
                    val choices = if (refresh) resolver.refreshPrepared(listing, revision) else resolver.prepare(listing, revision)
                    checkAllowed(revision)
                    if (remember && resolver.hasFresh(listing) && resolver.hasPreparedResult(listing, revision)) synchronized(this@LiveStreamPrefetcher) {
                        if (enabled && plan?.revision == revision) minimumDone += listing.id
                    }
                    if (!refresh) prepared(entry, choices)
                }
            } }.awaitAll()
        }
        val work = async(start = CoroutineStart.LAZY) {
            if (synchronized(this@LiveStreamPrefetcher) { initializedRevision != revision }) {
                setPhase(PreparationPhase.DISCOVERY)
                initialize() // Engine: bounded provider discovery, then fail-open ESPN scoreboard.
                checkAllowed(revision)
                synchronized(this@LiveStreamPrefetcher) { initializedRevision = revision }
            }
            val catalogue = snapshot()
            val live = LiveMatchPriority.select(catalogue, preferences(), time.wallMillis(), Int.MAX_VALUE)
            checkAllowed(revision)
            selected = live.take(limits.prefetchMatches)
            val nextPlan = Plan(revision, selected.map { it.listings.map(Listing::id) })
            synchronized(this@LiveStreamPrefetcher) {
                if (plan != nextPlan) {
                    plan = nextPlan
                    minimumDone.clear(); enrichmentDone.clear(); entriesEnriched.clear(); providerSlots.clear()
                }
                expanded.keys.retainAll(live.map { it.id }.toSet())
                priorityListings = selected.flatMap { it.listings }
            }
            selected.forEach { timings[it.id] = MatchPrefetchTiming(it.id) }
            val recent = synchronized(this@LiveStreamPrefetcher) { requested }?.let { old ->
                catalogue.firstOrNull { entry -> entry.id == old.id || entry.listings.any { item -> old.listings.any { it.id == item.id } } }
            }
            val protected = (listOfNotNull(recent) + selected).distinctBy { it.id }
            resolver.protectPrepared(protected.flatMap { it.listings })

            setPhase(PreparationPhase.PRIORITY_MINIMUM)
            // No listing-page cutoff: every provider for all four targets is attempted.
            // Finish each match's minimum set before moving to the next match.
            for (entry in selected) {
                val done = synchronized(this@LiveStreamPrefetcher) { entry.listings.all { it.id in minimumDone } }
                if (!done) minimum(entry, remember = true)
                if (synchronized(this@LiveStreamPrefetcher) { entry.listings.any { it.id !in minimumDone } }) return@async
                synchronized(progress) { timings[entry.id] = timings.getValue(entry.id).copy(minimumReadyMillis = time.monotonicMillis() - started) }
            }

            // A finite maintenance pass. Expiry does not undo completed phase barriers;
            // rechecking freshness forever would starve enrichment and expansion.
            var maintainedAt = Long.MIN_VALUE
            suspend fun maintain() {
                if (maintainedAt != Long.MIN_VALUE && time.monotonicMillis() - maintainedAt < limits.prefetchIntervalMillis) return
                val listings = protected.flatMap { it.listings }.distinctBy { it.id }
                val start = synchronized(this@LiveStreamPrefetcher) { maintenanceCursor % listings.size.coerceAtLeast(1) }
                val due = (listings.drop(start) + listings.take(start)).filter(resolver::needsRefresh).take(limits.activeResolutions)
                coroutineScope {
                    due.map { listing -> async {
                        checkAllowed(revision)
                        resolver.refreshPrepared(listing, revision)
                        checkAllowed(revision)
                    } }.awaitAll()
                }
                if (due.isNotEmpty()) synchronized(this@LiveStreamPrefetcher) {
                    maintenanceCursor = (listings.indexOfLast { it.id == due.last().id } + 1) % listings.size
                }
                maintainedAt = time.monotonicMillis()
            }
            maintain()
            setPhase(PreparationPhase.ENRICHMENT)
            val initialPass = synchronized(this@LiveStreamPrefetcher) { selected.any { it.id !in entriesEnriched } }
            var probeMillis = 0L
            enrichment@ for (entry in selected) {
                checkAllowed(revision)
                if (initialPass && synchronized(this@LiveStreamPrefetcher) { entry.id in entriesEnriched }) continue
                val slots = synchronized(this@LiveStreamPrefetcher) { providerSlots.getOrPut(entry.id) { linkedMapOf() } }
                // Prefer already-measured providers so another pass cannot grow the cap.
                val listings = entry.listings.sortedByDescending { resolver.hasAssessment(it) }
                for (listing in listings) {
                    checkAllowed(revision)
                    if (slots.size >= limits.measuredProvidersPerMatch) break
                    val choices = resolver.minimumObservation(listing, revision)
                    if (choices.any { it.url.isNotBlank() && isHls(it) }) slots.putIfAbsent(listing.source.kind, listing)
                }
                synchronized(progress) { timings[entry.id] = timings.getValue(entry.id).copy(measuredProviders = slots.keys.toSet()) }
                for (listing in slots.values) {
                    checkAllowed(revision)
                    if (initialPass && synchronized(this@LiveStreamPrefetcher) { listing.id in enrichmentDone }) continue
                    val remaining = limits.prefetchBatchMillis - probeMillis
                    if (remaining <= 0) break@enrichment
                    maintain() // Due minimum refreshes outrank the next expensive probe.
                    if (resolver.needsRefresh(listing)) resolver.refreshPrepared(listing, revision)
                    if (!resolver.hasPreparedResult(listing, revision)) continue
                    val probeStarted = time.monotonicMillis()
                    val completed = withTimeoutOrNull(remaining) {
                        try {
                            rated(entry, resolver.measurePrepared(listing, revision, 1))
                            if (limits.prefetchChoicesPerListing > 1)
                                rated(entry, resolver.measurePrepared(listing, revision, limits.prefetchChoicesPerListing))
                            true
                        } finally {
                            if (!currentCoroutineContext().isActive) resolver.cancelPrefetch(listing)
                        }
                    }
                    probeMillis += time.monotonicMillis() - probeStarted
                    if (completed == null) break@enrichment
                    checkAllowed(revision)
                    synchronized(this@LiveStreamPrefetcher) { enrichmentDone += listing.id }
                }
                if (synchronized(this@LiveStreamPrefetcher) { slots.values.all { it.id in enrichmentDone } })
                    synchronized(this@LiveStreamPrefetcher) { entriesEnriched += entry.id }
            }
            // Initial priority enrichment must have completed (including failed/timed-out
            // attempts) before expansion. Later refreshes cannot restart that barrier.
            if (synchronized(this@LiveStreamPrefetcher) { selected.any { it.id !in entriesEnriched } }) return@async
            setPhase(PreparationPhase.EXPANSION)
            val others = live.drop(limits.prefetchMatches).filter { entry ->
                synchronized(this@LiveStreamPrefetcher) { expanded[entry.id] != entry.listings.map { it.id }.toSet() }
            }.take(limits.prefetchExpansionMatches)
            for (entry in others) {
                checkAllowed(revision)
                maintain()
                minimum(entry)
                checkAllowed(revision)
                if (entry.listings.all { resolver.hasPreparedResult(it, revision) }) synchronized(this@LiveStreamPrefetcher) {
                    expanded[entry.id] = entry.listings.map { it.id }.toSet()
                }
            }
        }
        synchronized(this@LiveStreamPrefetcher) { batch = work; work.start() }
        try { work.await() }
        catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        finally {
            resolver.cancelPrefetches()
            synchronized(this@LiveStreamPrefetcher) {
                if (batch === work) batch = null
                if (phase != PreparationPhase.FOREGROUND && phase != PreparationPhase.PAUSED) phase = PreparationPhase.IDLE
            }
            synchronized(progress) { lastReport = PrefetchBatchReport(time.monotonicMillis() - started, preparedCount, timings.values.toList()) }
        }
        selected.map { it.id }
    }

    /** Playback pauses work without forgetting completed phases or requiring another browse. */
    fun pause() {
        val active = synchronized(this) { phase = PreparationPhase.PAUSED; batch }
        active?.cancel()
        resolver.cancelPrefetches()
    }

    /** Memory/settings invalidation requires new interaction (or configure/start) to resume. */
    fun reset() {
        val active = synchronized(this) {
            enabled = false; initializedRevision = null; plan = null; requested = null
            minimumDone.clear(); enrichmentDone.clear(); entriesEnriched.clear(); providerSlots.clear(); expanded.clear()
            priorityListings = emptyList()
            listOfNotNull(batch, foreground).also { foreground = null }
        }
        active.forEach { it.cancel() }
        resolver.cancelPrefetches()
    }

    fun stop() {
        val active = synchronized(this) { job.also { job = null } }
        active?.cancel()
        reset()
    }
}
