package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry

import app.futbolio.application.ports.*
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.*

@Serializable
internal data class LiveProviderReport(
    val providerId: String, val provider: String, val outcome: String = "NOT_RUN",
    val listings: Int = 0, val matches: Int = 0, val channels: Int = 0,
    val eligibleMatches: Int = 0, val attempts: Int = 0, val hlsChoices: Int = 0,
    val mediaObjects: Int = 0, val mediaBytes: Long = 0, val formats: List<String> = emptyList(),
    val elapsedMs: Long = 0, val detail: String = "", val observations: List<String> = emptyList(),
    val checkedAt: Long = System.currentTimeMillis(),
)

/** Test harness uses production discovery, resolution, session handling and media probing.
 * No signed URLs, credentials or exception messages are persisted.
 */
internal class LiveProviderVerifier(private val net: Upstream, private val budgetMs: Long = 60_000, private val providers: ProviderRegistry = ProviderRegistry()) {
    suspend fun verify(source: SourceConfig): LiveProviderReport {
        val started = System.currentTimeMillis()
        var report = LiveProviderReport(source.id, source.name)
        val notes = mutableListOf<String>()
        try {
            withTimeout(budgetMs) {
                val discovery = try { withTimeout(20_000) { DomainRecovery(net, providers).discover(source) } }
                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); report = report.copy(outcome = "DISCOVERY_TIMEOUT"); return@withTimeout }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { report = report.copy(outcome = "DISCOVERY_FAILED", detail = e.javaClass.simpleName); return@withTimeout }
                val now = System.currentTimeMillis()
                val matches = discovery.listings.filterNot { it.channel }
                val candidates = matches.filter { !it.expired(now) && (it.starts == 0L || it.starts <= now) }
                    .sortedWith(compareBy<Listing> { it.starts == 0L }.thenByDescending { it.starts })
                report = report.copy(listings = discovery.listings.size, matches = matches.size,
                    channels = discovery.listings.count { it.channel }, eligibleMatches = candidates.size)
                if (matches.isEmpty()) { report = report.copy(outcome = "NO_MATCHES"); return@withTimeout }
                if (candidates.isEmpty()) { report = report.copy(outcome = "NO_ACTIVE_MATCHES"); return@withTimeout }
                for (listing in candidates.take(3)) {
                    report = report.copy(attempts = report.attempts + 1)
                    // Unknown-start listings are allowed, but their live status is not assumed.
                    val identity = "match ${report.attempts} (${if (listing.starts == 0L) "start unknown" else "started"})"
                    try {
                        val success = withTimeout(12_000) { coroutineScope {
                            val choices = StreamResolver(this, net, providers).resolve(listing, force = true).filter { it.url.isNotEmpty() && isHls(it) }.take(2)
                            report = report.copy(hlsChoices = report.hlsChoices + choices.size)
                            if (choices.isEmpty()) notes += "$identity: no inline HLS (website fallback is not success)"
                            for (choice in choices) {
                                try {
                                    val segments = SegmentProbe(net).probe(choice, verifyMedia = true)
                                    require(segments.isNotEmpty())
                                    report = report.copy(outcome = "PASS", mediaObjects = segments.size,
                                        mediaBytes = segments.sumOf { it.bytes }.toLong(), formats = segments.map { it.format }.distinct())
                                    notes += "$identity: fetched and verified media"
                                    return@coroutineScope true
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { notes += "$identity: media failed (${e.javaClass.simpleName})" }
                            }
                            false
                        } }
                        if (success) break
                    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); notes += "$identity: attempt timed out" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notes += "$identity: resolution failed (${e.javaClass.simpleName})" }
                }
                if (report.outcome != "PASS") report = report.copy(outcome = if (report.hlsChoices == 0) "RESOLUTION_FAILED" else "SEGMENT_FAILED")
            }
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); report = report.copy(outcome = "PROVIDER_TIMEOUT") }
        return report.copy(elapsedMs = System.currentTimeMillis() - started, observations = notes.toList())
    }
}
