package app.futbolio.application.workbench

import app.futbolio.application.api.*
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.domain.identity.StableIdentity
import app.futbolio.domain.match.Leagues
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.provider.SourceUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

/** Diagnostic orchestration. Formats, media parsing, transport and storage are injected boundaries. */
class ProviderWorkbench(
    private val scope: CoroutineScope,
    private val net: Upstream,
    private val resolver: StreamResolver,
    private val providers: ProviderDirectory,
    private val media: MediaProbe,
    private val time: TimeSource = TimeSource.System,
) {
    private data class Playable(val row: Listing, val checkedAt: Long)
    private val playable = linkedMapOf<String, Playable>()
    private val mutableReports = MutableStateFlow<Map<String, ProviderTestReport>>(emptyMap())
    val reports = mutableReports.asStateFlow()
    private val mutableCandidates = MutableStateFlow<Map<String, List<ProbeCandidate>>>(emptyMap())
    val candidates = mutableCandidates.asStateFlow()
    private val gate = Semaphore(1)
    private var generation = 0L
    private val jobs = mutableSetOf<Job>()
    private var batch: Job? = null
    private val mutableBatch = MutableStateFlow(ProviderTestBatch())
    val batchState = mutableBatch.asStateFlow()

    @Synchronized fun startAll(sources: List<SourceConfig>): Job? {
        if (jobs.any { it.isActive }) return null
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val epoch = generation
        mutableBatch.value = ProviderTestBatch(true, total = enabled.size)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for ((index, source) in enabled.withIndex()) {
                    synchronized(this@ProviderWorkbench) {
                        if (epoch != generation) return@launch
                        mutableBatch.value = ProviderTestBatch(true, source.id, index, enabled.size)
                    }
                    test(source)
                    synchronized(this@ProviderWorkbench) {
                        if (epoch == generation) mutableBatch.value = ProviderTestBatch(true, completed = index + 1, total = enabled.size)
                    }
                }
            } finally {
                synchronized(this@ProviderWorkbench) {
                    if (epoch == generation) mutableBatch.value = mutableBatch.value.copy(running = false, activeProviderId = "")
                }
            }
        }
        batch = task
        jobs += task
        task.invokeOnCompletion { synchronized(this) { jobs -= task; if (batch === task) batch = null } }
        task.start()
        return task
    }

    @Synchronized fun start(source: SourceConfig, sampleId: String? = null): Job {
        batch?.takeIf { it.isActive }?.let { return it }
        val task = scope.launch(start = CoroutineStart.LAZY) { test(source, sampleId) }
        jobs += task
        task.invokeOnCompletion { synchronized(this) { jobs -= task } }
        task.start()
        return task
    }

    fun reset() {
        val obsolete = synchronized(this) {
            generation++
            playable.clear()
            mutableReports.value = emptyMap()
            mutableCandidates.value = emptyMap()
            mutableBatch.value = ProviderTestBatch()
            batch = null
            jobs.toList().also { jobs.clear() }
        }
        obsolete.forEach { it.cancel() }
    }

    suspend fun test(source: SourceConfig, sampleId: String? = null): ProviderTestReport {
        ConfigurationValidator(providers).validateSource(source)
        if (!gate.tryAcquire()) return ProviderTestReport(source.id, source.name, time.wallMillis(), ProbePhase.BUSY, error = "Another provider test is running")
        val epoch = synchronized(this) { generation }
        fun publish(report: ProviderTestReport) = synchronized(this) { if (epoch == generation) publishCurrent(report) }
        val now = time.wallMillis()
        var report = ProviderTestReport(source.id, source.name, now, ProbePhase.DISCOVERING)
        publish(report)
        try {
            withTimeout(75_000) {
                val discovery = withTimeout(35_000) { DomainRecovery(net, providers).discover(source) }
                synchronized(this@ProviderWorkbench) {
                    if (epoch == generation) mutableCandidates.value = mutableCandidates.value +
                        (source.id to discovery.listings.map { ProbeCandidate(it.id, it.title, it.league, it.channel) })
                }
                report = report.copy(phase = ProbePhase.RESOLVING, catalogCount = discovery.listings.size,
                    upcomingOnly = discovery.listings.isNotEmpty() && discovery.listings.all {
                        !it.channel && MatchNormalizer.kickoff(it, now) > now
                    },
                    activeDomain = SourceUrl.parse(discovery.activeUrl.ifBlank { source.url }).host, warning = discovery.warning)
                publish(report)
                val samples = if (sampleId != null) discovery.listings.filter { it.id == sampleId }.take(1) else
                    discovery.listings.sortedWith(compareBy<Listing> { it.channel }.thenBy {
                        val start = MatchNormalizer.kickoff(it, now)
                        if (start == 0L) Long.MAX_VALUE / 2 else kotlin.math.abs(start - now) + if (start > now + 15 * 60_000) 24 * 3_600_000 else 0
                    }).take(2)
                val results = mutableListOf<ListingTestResult>()
                for (row in samples) {
                    val entryId = "fut:probe:${StableIdentity.hash("${source.id}|${row.id}|$now")}"
                    val tested = mutableListOf<StreamTestResult>()
                    for ((index, choice) in resolver.resolve(row, force = true).filter { it.url.isNotEmpty() }.take(3).withIndex()) {
                        val id = StableIdentity.hash("${row.id}|${choice.title}|$index")
                        report = report.copy(phase = ProbePhase.FETCHING_SEGMENTS)
                        publish(report)
                        if (!isHls(choice)) tested += StreamTestResult(id, choice.title, ProbeStage.RESOLVED_DIRECT, error = "Non-HLS segment verification is not implemented; verify in Stremio")
                        else try {
                            tested += StreamTestResult(id, choice.title, ProbeStage.SEGMENTS_FETCHED, withTimeout(15_000) { media.probe(choice) })
                        } catch (_: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            tested += StreamTestResult(id, choice.title, ProbeStage.FAILED, error = "Stream probe timed out")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { tested += StreamTestResult(id, choice.title, ProbeStage.FAILED, error = Failures.describe(failure)) }
                        // Keep only the origin: paths and queries can contain playback credentials.
                        if (tested.isNotEmpty()) tested[tested.lastIndex] = tested.last().copy(
                            mediaUrl = app.futbolio.application.presentation.safeProviderUrl(choice.url))
                    }
                    synchronized(this@ProviderWorkbench) {
                        if (epoch == generation) {
                            playable[entryId] = Playable(row, time.monotonicMillis())
                            while (playable.size > 16) playable.remove(playable.keys.first())
                        }
                    }
                    results += ListingTestResult(entryId, row.title, tested, if (tested.isEmpty()) "No static media URL found; website-only" else "")
                    report = report.copy(listings = results.toList())
                    publish(report)
                }
                report = report.copy(phase = ProbePhase.COMPLETE)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            report = report.copy(phase = ProbePhase.FAILED, error = "Provider test deadline reached")
        } catch (cancelled: CancellationException) { report = report.copy(phase = ProbePhase.CANCELLED); throw cancelled }
        catch (failure: Exception) { report = report.copy(phase = ProbePhase.FAILED, error = Failures.describe(failure)) }
        finally { publish(report); gate.release() }
        return report
    }

    @Synchronized private fun publishCurrent(report: ProviderTestReport) { mutableReports.value = mutableReports.value + (report.providerId to report) }

    @Synchronized fun confirmPlayback(providerId: String, choiceId: String, works: Boolean) {
        val old = mutableReports.value[providerId] ?: return
        publishCurrent(old.copy(listings = old.listings.map { row -> row.copy(choices = row.choices.map {
            if (it.id == choiceId) it.copy(playback = if (works) PlaybackStatus.CONFIRMED_BY_USER else PlaybackStatus.FAILED_IN_PLAYER,
                playbackCheckedAt = time.wallMillis()) else it
        }) }))
    }

    @Synchronized fun entries(): List<CatalogEntry> = playable.filterValues { time.monotonicMillis() - it.checkedAt < 1_800_000 }.map { (id, value) ->
        CatalogEntry(id, "[Test · ${value.row.source.name}] ${value.row.title}", Leagues.find(value.row.league), value.row.starts, listOf(value.row))
    }

    suspend fun streams(id: String): List<StreamChoice> {
        val resolverRevision = resolver.revision()
        val (entry, epoch) = synchronized(this) { (playable[id] ?: return emptyList()) to generation }
        if (time.monotonicMillis() - entry.checkedAt >= 1_800_000) return emptyList()
        val choices = resolver.resolve(entry.row, force = true, expectedRevision = resolverRevision).filter { it.url.isNotEmpty() }.take(3)
        if (synchronized(this) { epoch != generation }) return emptyList()
        return choices.map { it.copy(providers = listOf(entry.row.source.name)) } +
            StreamChoice("Open website (external browser)", externalUrl = entry.row.page, providers = listOf(entry.row.source.name))
    }
}
