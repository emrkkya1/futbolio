package app.futbolio.application.fixtures

import app.futbolio.application.api.Failures
import app.futbolio.application.ports.ScoreFeed
import app.futbolio.application.ports.TimeSource
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** On-demand fixture identity/status only. No scoreboard sweep, scores, or periodic polling. */
class FixtureService(
    private val scope: CoroutineScope,
    private val feed: ScoreFeed,
    private val time: TimeSource = TimeSource.System,
) {
    private data class Key(val league: League, val date: LocalDate)
    private data class Cached(val fixtures: List<ScoreFixture>, val until: Long)
    private val cache = linkedMapOf<Key, Cached>()
    private val pending = mutableMapOf<Key, Deferred<Unit>>()
    private val permits = Semaphore(2)
    private var generation = 0L
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    @Volatile var lastError = ""
        private set

    fun reset() {
        val obsolete = synchronized(this) {
            generation++
            cache.clear()
            lastError = ""
            mutableChanges.value++
            pending.values.toList().also { pending.clear() }
        }
        obsolete.forEach { it.cancel() }
    }

    @Synchronized fun fixtures() = cache.values.flatMap { it.fixtures }

    /** Only call for a selected match or a detected duplicate conflict, never ordinary rows. */
    fun request(entry: CatalogEntry, config: PresentationConfig): List<Deferred<Unit>> {
        if (entry.channel || entry.home == null || entry.away == null) return emptyList()
        val now = time.wallMillis()
        val candidates = listOf(entry.league to entry.starts) + entry.listings.map {
            Leagues.find(it.league, config.leagueAliases) to MatchNormalizer.kickoff(it, now)
        }
        return candidates.filter { (league, start) -> Leagues.allowed(league) && start > 0 &&
            start <= now + CatalogVisibility.UPCOMING && start + 3 * CatalogVisibility.HOUR > now }
            .map { (league, start) -> Key(league, Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()) }
            .distinct().take(4).mapNotNull(::request)
    }

    @Synchronized private fun request(key: Key): Deferred<Unit>? {
        pending[key]?.let { return it }
        if (cache[key]?.until?.let { it > time.monotonicMillis() } == true || pending.size >= 12) return null
        val epoch = generation
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(1500) {
                    permits.withPermit { feed.fixtures(key.league, key.date, time.wallMillis()) }
                }.take(100).map { it.copy(homeScore = "", awayScore = "", detail = "") }
                synchronized(this@FixtureService) {
                    if (epoch == generation) {
                        // Preserve confirmed final statuses across later incomplete/failed responses.
                        val finals = cache[key]?.fixtures.orEmpty().filter { it.state in finalStates }
                        val merged = result.filterNot { fresh -> finals.any { it.id == fresh.id } } + finals
                        cache[key] = Cached(merged, time.monotonicMillis() + 60_000)
                        while (cache.size > 48) cache.remove(cache.keys.first())
                        lastError = ""
                        mutableChanges.value++
                    }
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failure(key, epoch, "Fixture source timed out")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(key, epoch, Failures.describe(error)) }
            finally { synchronized(this@FixtureService) { if (epoch == generation) pending.remove(key) } }
        }
        pending[key] = job
        job.start()
        return job
    }

    @Synchronized private fun failure(key: Key, epoch: Long, message: String) {
        if (epoch != generation) return
        cache[key] = Cached(cache[key]?.fixtures.orEmpty(), time.monotonicMillis() + 300_000)
        while (cache.size > 48) cache.remove(cache.keys.first())
        lastError = message
    }

    private companion object {
        val finalStates = setOf(MatchState.FINISHED, MatchState.CANCELLED)
    }
}
