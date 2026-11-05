package app.futbolio.engine.adapters.posters

import app.futbolio.application.api.*
import app.futbolio.application.catalog.EntryCatalog
import app.futbolio.application.ports.TimeSource
import app.futbolio.domain.settings.DisplayTime
import app.futbolio.domain.match.MatchState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Snapshot-only, separately bounded rendering. Never performs discovery, score or logo requests. */
internal class PosterStore(
    private val entries: EntryCatalog,
    private val renderer: PosterRenderer,
    private val time: TimeSource,
    private val limits: ResourceLimits,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Image(val model: PosterModel, val bytes: ByteArray)
    private val cache = LinkedHashMap<String, Image>(32, .75f, true)
    private val renderers = Semaphore(limits.posterRenderSlots)
    private val requests = Semaphore(64)
    private var generation = 0L

    suspend fun image(id: String): ByteArray? {
        if (!requests.tryAcquire()) return null
        try {
            val epoch = synchronized(cache) { generation }
            val item = entries.posterEntry(id)
            val preferences = entries.presentation()
            val zone = ZoneId.of(preferences.displayTimeZone)
            val kickoff = item?.starts?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val model = if (item == null) PosterModel("Live Football", "Futbolio", background = BundledArtwork.background) else PosterModel(item.title, item.league.name,
                item.home?.name.orEmpty(), item.away?.name.orEmpty(), status = item.state(time.wallMillis()).label,
                kickoff = DisplayTime.kickoff(item.starts, preferences),
                homeLogo = item.home?.name?.let(BundledArtwork::team), awayLogo = item.away?.name?.let(BundledArtwork::team),
                competitionLogo = BundledArtwork.competition(item.league.id), background = BundledArtwork.background,
                kickoffTime = kickoff?.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK)).orEmpty(),
                kickoffDate = kickoff?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.UK))?.uppercase(Locale.UK)
                    ?.let { "$it · UTC${kickoff.offset.id.takeUnless { offset -> offset == "Z" } ?: "+00:00"}" }.orEmpty(),
                live = item.state(time.wallMillis()) in listOf(MatchState.LIVE, MatchState.HALFTIME),
                statusVerified = item.fixture?.let { it.fresh(time.wallMillis()) || it.state in listOf(MatchState.FINISHED, MatchState.CANCELLED) } == true,
                competitionLightBackground = BundledArtwork.competitionLightBackground(item.league.id),
                channelLogo = if (item.home == null && item.away == null) BundledArtwork.channel(item.title) else null,
                score = if (preferences.showTimesAndScores) item.fixture?.scoreText(time.wallMillis()).orEmpty() else "",
                minute = if (preferences.showTimesAndScores) item.fixture?.clockText(time.wallMillis()).orEmpty() else "")
            return renderers.withPermit {
                synchronized(cache) { cache[id]?.takeIf { it.model == model }?.bytes }
                    ?: withContext(cpu) { renderer.renderMatch(model) }.also { bytes ->
                        synchronized(cache) {
                            if (epoch == generation) {
                                cache[id] = Image(model, bytes)
                                while (cache.size > limits.posterEntries) cache.remove(cache.keys.first())
                            }
                        }
                    }
            }
        } finally { requests.release() }
    }

    fun clear() = synchronized(cache) { generation++; cache.clear() }
}
