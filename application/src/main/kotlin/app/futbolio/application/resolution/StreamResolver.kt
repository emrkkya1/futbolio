package app.futbolio.application.resolution

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.api.Failures
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Single-flight, bounded resolution. No provider formats, extraction logic or concrete registry. */
class StreamResolver(
    private val scope: CoroutineScope,
    private val net: Upstream,
    private val providers: ProviderDirectory,
    private val time: TimeSource = TimeSource.System,
    private val limits: ResourceLimits = ResourceLimits(),
    private val events: EventSink = EventSink {},
    private val assessor: StreamAssessor = StreamAssessor.None,
    private val measurementAllowed: () -> Boolean = { true },
) {
    private data class Cached(val choices: List<StreamChoice>, val until: Long, val attempted: Set<Int> = emptySet(),
        val firstMeasurement: Long = Long.MAX_VALUE, val assessmentUntil: Long = 0)
    private class Pending(@Volatile var foreground: Boolean, val prepareOnly: Boolean = false, val choiceLimit: Int? = null) {
        lateinit var task: Deferred<List<StreamChoice>>
        val prepared = CompletableDeferred<Unit>()
        var stage: Job? = null
        var firstMeasurement = Long.MAX_VALUE
        val attempted = mutableSetOf<Int>()
        var preparedUntil = Long.MAX_VALUE
        var assessmentUntil = 0L
        @Volatile var choices: List<StreamChoice> = emptyList()
    }
    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)
    private val pending = mutableMapOf<String, Pending>()
    private val assessments = mutableSetOf<Job>()
    private var generation = 0L
    private var foregroundUntil = 0L
    private val permits = Semaphore(limits.activeResolutions)
    private val assessmentPermits = Semaphore(limits.activeStreamAssessments)

    @Synchronized fun revision(): Long = generation
    @Synchronized fun hasFresh(item: Listing) = cache[item.id]?.until?.let { it > time.monotonicMillis() } == true
    @Synchronized fun needsRefresh(item: Listing): Boolean = pending[item.id]?.task?.isActive != true &&
        (cache[item.id]?.until ?: 0) <= time.monotonicMillis() + limits.prefetchRefreshAheadMillis
    @Synchronized fun hasAssessment(item: Listing, choices: Int = 1): Boolean {
        val cached = cache[item.id]?.takeIf { it.until > time.monotonicMillis() } ?: return false
        return cached.assessmentUntil > time.monotonicMillis() && cached.choices.withIndex()
            .filter { it.value.externalUrl.isEmpty() && isHls(it.value) }.take(choices).all { it.index in cached.attempted }
    }
    private fun currentChoices(cached: Cached): List<StreamChoice> = if (cached.assessmentUntil > 0 && cached.assessmentUntil <= time.monotonicMillis())
        cached.choices.map { it.copy(assessment = StreamAssessment()) } else cached.choices

    /** One immutable, revision-fenced snapshot; no network or measurement joins. */
    @Synchronized fun preparedSnapshot(items: List<Listing>, expectedRevision: Long): List<StreamChoice>? {
        if (expectedRevision != generation) return emptyList()
        val ready = items.map { item -> cache[item.id]?.takeIf { it.until > time.monotonicMillis() }?.let { cached ->
            currentChoices(cached).map { it.copy(providers = listOf(item.source.name)) }
        } }
        val choices = ready.filterNotNull().flatten()
        return choices.takeIf { ready.all { it != null } }
    }

    /** Resolve every provider's links, exactly as before; never join metadata/speed checks. */
    suspend fun awaitPreparedSnapshot(items: List<Listing>, expectedRevision: Long): List<StreamChoice> {
        preparedSnapshot(items, expectedRevision)?.let { return it }
        prioritize(items, expectedRevision)
        val prepared = coroutineScope {
            val slots = Semaphore(limits.activeResolutions)
            items.map { item -> async { slots.withPermit { prepare(item, expectedRevision, background = false) } } }.awaitAll()
        }
        return synchronized(this) {
            if (generation != expectedRevision) return@synchronized emptyList()
            // Use newer observations when available. If another provider took
            // longer than cache TTL, preserve the links extracted for this very
            // request instead of dropping a playable source to await more data.
            items.zip(prepared).flatMap { (item, choices) ->
                val cached = cache[item.id]?.takeIf { it.until > time.monotonicMillis() }
                (cached?.let(::currentChoices) ?: choices.map { it.copy(assessment = StreamAssessment()) })
                    .map { it.copy(providers = listOf(item.source.name)) }
            }
        }
    }

    suspend fun refreshPrepared(item: Listing, expectedRevision: Long, background: Boolean = true): List<StreamChoice> {
        val refresh = synchronized(this) { (cache[item.id]?.until ?: 0) <= time.monotonicMillis() + limits.prefetchRefreshAheadMillis }
        return resolveInternal(item, force = refresh, expectedRevision = expectedRevision, background = background, prepareOnly = true)
    }
    @Synchronized fun prefetchAllowed() = measurementAllowed() && time.monotonicMillis() >= foregroundUntil && pending.values.none { it.foreground && it.task.isActive }

    /** Shared work is promoted when selected; speculative work for other matches yields immediately. */
    fun prioritize(items: List<Listing>, expectedRevision: Long? = null) = foregroundInterest(items.map { it.id }, expectedRevision)

    private fun foregroundInterest(ids: List<String>, expectedRevision: Long? = null) {
        val obsolete = synchronized(this) {
            if (expectedRevision != null && expectedRevision != generation) return
            foregroundUntil = time.monotonicMillis() + limits.foregroundQuietMillis
            ids.forEach { id -> pending[id]?.foreground = true }
            val background = pending.filterValues { !it.foreground }
            background.keys.forEach(pending::remove)
            background.values.map { it.task }
        }
        obsolete.forEach { it.cancel() }
    }

    fun cancelPrefetches() {
        val obsolete = synchronized(this) {
            val background = pending.filterValues { !it.foreground }
            background.keys.forEach(pending::remove)
            background.values.map { it.task }
        }
        obsolete.forEach { it.cancel() }
    }

    fun cancelPrefetch(item: Listing) {
        val obsolete = synchronized(this) { pending[item.id]?.takeUnless { it.foreground }?.also { pending.remove(item.id) }?.task }
        obsolete?.cancel()
    }

    /** Cancels only measurement stages; foreground resolution still returns its playable choices. */
    fun pauseMeasurements() {
        synchronized(this) { assessments.toList() }.forEach { it.cancel() }
        cancelPrefetches()
    }

    suspend fun prefetch(item: Listing, expectedRevision: Long): List<StreamChoice> =
        resolveInternal(item, force = false, expectedRevision = expectedRevision, background = true)

    /** Link preparation ends before any speed sampling, freeing the provider-resolution slot. */
    suspend fun prepare(item: Listing, expectedRevision: Long, background: Boolean = true): List<StreamChoice> =
        resolveInternal(item, force = false, expectedRevision = expectedRevision, background = background, prepareOnly = true)

    /** Enrich only an already-prepared URL/session; never re-extract an expired stream here. */
    suspend fun measurePrepared(item: Listing, expectedRevision: Long, choices: Int = 1, background: Boolean = true): List<StreamChoice> {
        require(choices > 0)
        return resolveInternal(item, force = false, expectedRevision = expectedRevision, background = background, measurementChoices = choices)
    }

    suspend fun resolve(item: Listing, force: Boolean = false, expectedRevision: Long? = null): List<StreamChoice> {
        return resolveInternal(item, force, expectedRevision, background = false)
    }

    private suspend fun resolveInternal(item: Listing, force: Boolean, expectedRevision: Long?, background: Boolean,
        prepareOnly: Boolean = false, measurementChoices: Int? = null): List<StreamChoice> {
        currentCoroutineContext().ensureActive()
        if (!background) foregroundInterest(listOf(item.id), expectedRevision)
        val fallback = StreamChoice("Open website (external browser, not in-player)", externalUrl = item.page)
        val work = synchronized(this) {
            if (expectedRevision != null && expectedRevision != generation) return emptyList()
            val cached = cache[item.id]?.takeIf { it.until > time.monotonicMillis() }
            if (!force && cached != null && (measurementChoices == null || hasAssessment(item, measurementChoices))) return currentChoices(cached)
            if (measurementChoices != null && cached == null) return emptyList()
            if (background && !prefetchAllowed()) return emptyList()
            if (prepareOnly && pending[item.id]?.let { it.choiceLimit != null && it.preparedUntil <= time.monotonicMillis() } == true) {
                pending.remove(item.id)?.task?.cancel()
            }
            pending[item.id]?.takeUnless { it.task.isCancelled } ?: run {
                if (pending.size >= limits.pendingResolutions) return listOf(fallback)
                val epoch = generation
                val work = Pending(foreground = !background, prepareOnly = prepareOnly, choiceLimit = measurementChoices)
                if (measurementChoices != null && cached != null) {
                    if (cached.assessmentUntil > time.monotonicMillis()) {
                        work.attempted += cached.attempted
                        work.firstMeasurement = cached.firstMeasurement
                        work.assessmentUntil = cached.assessmentUntil
                    }
                    work.preparedUntil = cached.until
                }
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        var resolved = if (measurementChoices != null) currentChoices(requireNotNull(cached)) else
                            withTimeoutOrNull(limits.resolutionTimeoutMillis) { permits.withPermit { load(item) } }
                                ?: listOf(fallback).also { events.event("Resolution ${item.source.id}: deadline reached") }
                        if (measurementChoices == null) {
                            work.preparedUntil = time.monotonicMillis() + limits.resolutionCacheMillis
                            // Carry evidence only for the exact same URL, credentials and session.
                            // Renewing extraction must not reset sample age or resurrect stale grades.
                            synchronized(this@StreamResolver) { cache[item.id] }?.takeIf { it.assessmentUntil > time.monotonicMillis() }?.let { previous ->
                                resolved = resolved.mapIndexed { index, choice ->
                                    val old = previous.choices.withIndex().firstOrNull { (_, old) ->
                                        choice.url.isNotBlank() && choice.url == old.url && choice.headers == old.headers && choice.session === old.session
                                    }
                                    if (old != null) {
                                        if (old.index in previous.attempted) work.attempted += index
                                        work.firstMeasurement = previous.firstMeasurement
                                        work.assessmentUntil = previous.assessmentUntil
                                        choice.copy(assessment = old.value.assessment)
                                    } else choice
                                }
                            }
                        }
                        work.choices = resolved
                        if (background && prepareOnly) publish(item, work, epoch, resolved, progressive = true)
                        work.prepared.complete(Unit)
                        val choices = if (prepareOnly) resolved else assess(resolved, work) { observed -> publish(item, work, epoch, observed, progressive = true) }
                        publish(item, work, epoch, choices)
                        choices
                    } finally {
                        work.prepared.complete(Unit)
                        synchronized(this@StreamResolver) { if (pending[item.id] === work) pending.remove(item.id) }
                    }
                }.also { task ->
                    work.task = task
                    pending[item.id] = work
                    // A canceled scope can finish a lazy task without ever entering its body.
                    task.invokeOnCompletion {
                        work.prepared.complete(Unit)
                        synchronized(this@StreamResolver) { if (pending[item.id] === work) pending.remove(item.id) }
                    }
                    task.start()
                }
                work
            }
        }
        if (prepareOnly && !work.prepareOnly) {
            work.prepared.await()
            work.task.takeIf { it.isCancelled }?.await()
            return work.choices
        }
        if (!background) {
            work.prepared.await()
            withTimeoutOrNull(limits.streamAssessmentBatchMillis) { work.task.await() }?.let { return it }
            // Selecting an in-flight warm-up never inherits its longer speculative deadline.
            synchronized(this) { work.stage }?.cancel()
        }
        return work.task.await()
    }

    private fun publish(item: Listing, work: Pending, epoch: Long, choices: List<StreamChoice>, progressive: Boolean = false) = synchronized(this) {
        if (epoch != generation || pending[item.id] !== work) return@synchronized
        // Link lifetime and measurement freshness are independent. Probing never
        // extends a signed URL's lifetime; refreshing a URL never resets sample age.
        if (work.preparedUntil <= time.monotonicMillis()) return@synchronized
        cache[item.id] = Cached(choices.toList(), work.preparedUntil, work.attempted.toSet(), work.firstMeasurement, work.assessmentUntil)
        while (cache.size > limits.resolutionCacheEntries) cache.remove(cache.keys.first())
    }

    private suspend fun load(item: Listing): List<StreamChoice> {
        val choices = try { providers.get(item.source.kind).resolve(item, net) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { events.event("Resolution ${item.source.id}: ${Failures.describe(failure)}"); emptyList() }
        events.event("Resolution ${item.source.id}: ${choices.count { it.url.isNotBlank() }} media choices")
        return deduplicateStreams(choices.filter { it.url.isNotBlank() }).take(12) +
            StreamChoice("Open website (external browser, not in-player)", externalUrl = item.page)
    }

    /** Separate deadline: an inconclusive check must never discard resolved playback options. */
    private suspend fun assess(choices: List<StreamChoice>, work: Pending, update: (List<StreamChoice>) -> Unit): List<StreamChoice> = coroutineScope {
        if (!measurementAllowed()) return@coroutineScope choices
        val results = choices.toMutableList()
        fun observed(assessment: StreamAssessment): StreamAssessment {
            if (assessment.samples > 0) synchronized(this@StreamResolver) {
                if (work.firstMeasurement == Long.MAX_VALUE) work.assessmentUntil = time.monotonicMillis() + limits.prefetchCacheMillis
                work.firstMeasurement = minOf(work.firstMeasurement, time.monotonicMillis())
            }
            return assessment.copy(checkedAtMillis = if (assessment.samples > 0) time.wallMillis() else 0)
        }
        val background = !work.foreground
        val attempted = synchronized(this@StreamResolver) { work.attempted.toSet() }
        val stage = async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(if (background) limits.prefetchBatchMillis else limits.streamAssessmentBatchMillis) {
                choices.withIndex().filter { it.value.externalUrl.isEmpty() && isHls(it.value) }
                    .take(work.choiceLimit ?: if (background) limits.prefetchChoicesPerListing else limits.assessedChoicesPerListing)
                    .filter { it.index !in attempted }.map { (index, choice) -> launch {
                        assessmentPermits.withPermit {
                            if (!measurementAllowed() || time.monotonicMillis() >= work.preparedUntil) return@withPermit
                            val assessment = withTimeoutOrNull(limits.streamAssessmentMillis) {
                                try { assessor.assess(choice) {
                                    results[index] = choice.copy(assessment = observed(it))
                                    update(results)
                                } }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { StreamAssessment(status = ConnectionStatus.UNAVAILABLE) }
                            }
                            if (assessment != null) results[index] = choice.copy(assessment = observed(assessment))
                            synchronized(this@StreamResolver) {
                                work.attempted += index
                                if (work.assessmentUntil == 0L) work.assessmentUntil = time.monotonicMillis() + limits.resolutionCacheMillis
                            }
                            update(results)
                        }
                    } }.joinAll()
            }
        }
        synchronized(this@StreamResolver) {
            assessments += stage
            work.stage = stage
            if (measurementAllowed()) stage.start() else stage.cancel()
        }
        try { stage.await() }
        catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        finally { synchronized(this@StreamResolver) { assessments -= stage } }
        results
    }

    fun trim() {
        val obsolete = synchronized(this) {
            generation++
            cache.clear()
            pending.values.map { it.task }.also { pending.clear() }
        }
        obsolete.forEach { it.cancel() }
    }
}
