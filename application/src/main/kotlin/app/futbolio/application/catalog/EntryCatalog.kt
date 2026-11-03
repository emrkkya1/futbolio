package app.futbolio.application.catalog

import app.futbolio.application.ports.EventSink
import app.futbolio.application.ports.TimeSource
import app.futbolio.application.fixtures.FixtureService
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.ScoreFixture
import app.futbolio.domain.match.Leagues
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Aggregated queries, legacy aliases and snapshot-only poster metadata. No HTTP or rendering. */
class EntryCatalog(
    private val catalog: Catalog,
    private val fixtures: FixtureService,
    presentation: PresentationConfig,
    scope: CoroutineScope,
    private val time: TimeSource = TimeSource.System,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val events: EventSink = EventSink {},
) {
    private data class Cached(val revision: Long, val rows: List<Listing>, val fixtures: List<ScoreFixture>,
        val entries: List<CatalogEntry>)
    private val aggregation = Mutex()
    private val state = Any()
    private var revision = 0L
    private var options = presentation
    private val aliases = linkedMapOf<String, Set<String>>()
    private val posterEntries = linkedMapOf<String, CatalogEntry>()
    private var cache: Cached? = null
    private val settingsChanges = MutableStateFlow(0L)

    init {
        scope.launch {
            combine(catalog.changes, fixtures.changes, settingsChanges) { _, _, _ -> Unit }
                .collectLatest { rebuild() }
        }
    }

    fun configure(presentation: PresentationConfig) = synchronized(state) {
        presentation.validate()
        revision++
        options = presentation
        aliases.clear()
        posterEntries.clear()
        cache = null
        fixtures.reset()
        settingsChanges.value++
    }

    fun presentation() = synchronized(state) { options }
    fun posterEntry(id: String): CatalogEntry? = synchronized(state) { posterEntries[id] }

    suspend fun entries(includeHidden: Boolean = false): List<CatalogEntry> {
        catalog.refresh() // Fire-and-forget. Even an empty cold catalog never waits for a provider.
        val result = rebuild()
        return if (includeHidden) result else result.filter { CatalogVisibility.visible(it, time.wallMillis()) }
    }

    private suspend fun rebuild(): List<CatalogEntry> {
        val (epoch, preferences) = synchronized(state) { revision to options }
        val started = time.monotonicMillis()
        val providerVersion = catalog.changes.value
        val fixtureVersion = fixtures.changes.value
        val rows = catalog.items()
        val knownFixtures = fixtures.fixtures()
        return aggregation.withLock {
            synchronized(state) {
                if (epoch != revision) return@withLock emptyList()
                cache?.takeIf { it.revision == epoch && it.rows == rows && it.fixtures == knownFixtures }
                    ?.let { return@withLock it.entries }
            }
            val result = withContext(cpu) {
                MatchNormalizer.aggregate(rows, knownFixtures, preferences.copy(hideFinished = false, showHalftime = true), time.wallMillis())
            }
            synchronized(state) {
                if (epoch != revision) return@withLock emptyList()
                if (providerVersion != catalog.changes.value || fixtureVersion != fixtures.changes.value) return@withLock emptyList()
                cache = Cached(epoch, rows, knownFixtures, result)
                for (entry in result) {
                    aliases[entry.id] = entry.listings.map { it.id }.toSet()
                    posterEntries[entry.id] = entry
                }
                while (aliases.size > 2048) aliases.remove(aliases.keys.first())
                while (posterEntries.size > 2048) posterEntries.remove(posterEntries.keys.first())
                // Revision-fenced initiation: obsolete aggregation cannot restart fixture work.
                result.filter { it.needsFixtureLookup && it.listings.any { row ->
                    Leagues.allowed(Leagues.find(row.league, preferences.leagueAliases))
                } }.forEach { fixtures.request(it, preferences) }
            }
            events.event("Catalog snapshot: ${result.size} entries ready in ${time.monotonicMillis() - started} ms")
            result
        }
    }

    suspend fun find(id: String): CatalogEntry? {
        val old = synchronized(state) { aliases[id].orEmpty() }
        return entries(includeHidden = true).find { it.id == id || it.listings.any { row -> row.id == id || row.id in old } }?.copy(id = id)
    }

    /** Explicit selection is the only other fixture-lookup trigger. Streams never wait for it. */
    suspend fun selected(id: String, waitMillis: Long = 0): CatalogEntry? {
        val epoch = synchronized(state) { revision }
        val row = find(id) ?: return null
        val work = synchronized(state) { if (epoch != revision) return null else fixtures.request(row, options) }
        if (waitMillis > 0 && work.isNotEmpty()) {
            try { kotlinx.coroutines.withTimeoutOrNull(waitMillis) { work.forEach { it.await() } } }
            catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
            return find(id)
        }
        return row
    }

    fun clear() = synchronized(state) { revision++; aliases.clear(); posterEntries.clear(); cache = null; settingsChanges.value++ }
}
