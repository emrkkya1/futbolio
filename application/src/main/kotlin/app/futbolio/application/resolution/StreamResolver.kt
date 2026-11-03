package app.futbolio.application.resolution

import app.futbolio.application.api.ResourceLimits
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
) {
    private data class Cached(val choices: List<StreamChoice>, val until: Long)
    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)
    private val pending = mutableMapOf<String, Deferred<List<StreamChoice>>>()
    private var generation = 0L
    private val permits = Semaphore(limits.activeResolutions)

    @Synchronized fun revision(): Long = generation

    suspend fun resolve(item: Listing, force: Boolean = false, expectedRevision: Long? = null): List<StreamChoice> {
        currentCoroutineContext().ensureActive()
        val fallback = StreamChoice("Open website (external browser, not in-player)", externalUrl = item.page)
        val task = synchronized(this) {
            if (expectedRevision != null && expectedRevision != generation) return emptyList()
            if (!force) cache[item.id]?.takeIf { it.until > time.monotonicMillis() }?.let { return it.choices }
            pending[item.id] ?: run {
                if (pending.size >= limits.pendingResolutions) return listOf(fallback)
                val epoch = generation
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val choices = withTimeoutOrNull(limits.resolutionTimeoutMillis) { permits.withPermit { load(item) } } ?: listOf(fallback)
                        synchronized(this@StreamResolver) {
                            if (epoch == generation) {
                                cache[item.id] = Cached(choices, time.monotonicMillis() + limits.resolutionCacheMillis)
                                while (cache.size > limits.resolutionCacheEntries) cache.remove(cache.keys.first())
                            }
                        }
                        choices
                    } finally { synchronized(this@StreamResolver) { if (epoch == generation) pending.remove(item.id) } }
                }.also { pending[item.id] = it; it.start() }
            }
        }
        return task.await()
    }

    private suspend fun load(item: Listing): List<StreamChoice> {
        val choices = try { providers.get(item.source.kind).resolve(item, net) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        return deduplicateStreams(choices.filter { it.url.isNotBlank() }).take(12) +
            StreamChoice("Open website (external browser, not in-player)", externalUrl = item.page)
    }

    fun trim() {
        val obsolete = synchronized(this) {
            generation++
            cache.clear()
            pending.values.toList().also { pending.clear() }
        }
        obsolete.forEach { it.cancel() }
    }
}
