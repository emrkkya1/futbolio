package app.futbolio.application.presentation

import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig

enum class HealthTone { NEUTRAL, GOOD, WARNING, BAD, CHECKING }
data class HealthValue(val text: String, val tone: HealthTone)
data class ProviderCardState(val connected: HealthValue, val catalog: HealthValue, val fetching: HealthValue)

/** Shared display policy; a catalog or fetched segments must never imply confirmed native playback. */
fun providerCardState(source: SourceConfig, runtime: RuntimeSnapshot): ProviderCardState {
    fun value(text: String, tone: HealthTone = HealthTone.NEUTRAL) = HealthValue(text, tone)
    if (!source.enabled) return ProviderCardState(value("Disabled"), value("Disabled"), value("Disabled"))
    if (!runtime.running) return ProviderCardState(value("Relay stopped"), value("Not checked"), value("Not checked"))
    val status = runtime.providers.firstOrNull { it.id == source.id }
    val report = runtime.reports[source.id]
    val testing = report?.phase in listOf(ProbePhase.DISCOVERING, ProbePhase.RESOLVING, ProbePhase.FETCHING_SEGMENTS) ||
        runtime.testBatch.activeProviderId == source.id
    val connected = when {
        status?.ok == true || (report?.catalogCount ?: 0) > 0 -> value("Connected", HealthTone.GOOD)
        status?.loading == true || report?.phase == ProbePhase.DISCOVERING -> value("Connecting…", HealthTone.CHECKING)
        (status?.checked ?: 0) > 0 || report?.phase == ProbePhase.FAILED -> value("Unavailable", HealthTone.BAD)
        else -> value("Not checked")
    }
    val catalog = when {
        status?.ok == true -> value("${status.count} listings", HealthTone.GOOD)
        (status?.count ?: 0) > 0 -> value("${status!!.count} cached", HealthTone.WARNING)
        (report?.catalogCount ?: 0) > 0 -> value("${report!!.catalogCount} listings", HealthTone.GOOD)
        status?.loading == true || report?.phase == ProbePhase.DISCOVERING -> value("Loading…", HealthTone.CHECKING)
        (status?.checked ?: 0) > 0 || report?.phase == ProbePhase.FAILED -> value("Unavailable", HealthTone.BAD)
        else -> value("Not checked")
    }
    val choices = report?.listings.orEmpty().flatMap { it.choices }
    val fetching = when {
        testing -> value("Testing…", HealthTone.CHECKING)
        choices.any { it.stage == ProbeStage.SEGMENTS_FETCHED && it.segments.any { segment -> segment.bytes > 0 && segment.format.isNotBlank() } } -> value("True", HealthTone.GOOD)
        report?.phase == ProbePhase.CANCELLED -> value("Cancelled", HealthTone.WARNING)
        choices.any { it.stage == ProbeStage.FAILED || it.stage == ProbeStage.SEGMENTS_FETCHED } -> value("Fetch failed", HealthTone.BAD)
        report?.phase == ProbePhase.FAILED -> value("Test failed", HealthTone.BAD)
        choices.any { it.stage == ProbeStage.RESOLVED_DIRECT } -> value("Not verified", HealthTone.WARNING)
        report?.phase == ProbePhase.COMPLETE && report.upcomingOnly -> value("Waiting for live", HealthTone.NEUTRAL)
        report?.phase == ProbePhase.COMPLETE && report.catalogCount == 0 -> value("No listings", HealthTone.NEUTRAL)
        report?.phase == ProbePhase.COMPLETE -> value("No stream available", HealthTone.WARNING)
        else -> value("Not checked")
    }
    return ProviderCardState(connected, catalog, fetching)
}

/** Origins only: even URL paths may carry signed tokens. Never expose credentials in the UI/report. */
fun safeProviderUrl(url: String): String = try {
    val uri = java.net.URI(url)
    if (uri.scheme !in listOf("http", "https") || uri.host == null) "Unavailable"
    else "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}/…"
} catch (_: Exception) { "Unavailable" }

fun providerDetails(source: SourceConfig, runtime: RuntimeSnapshot): List<Pair<String, String>> {
    val status = runtime.providers.firstOrNull { it.id == source.id }
    val report = runtime.reports[source.id]
    val health = providerCardState(source, runtime)
    return buildList {
        add("Configured website" to safeProviderUrl(source.url))
        add("Active website" to (status?.activeUrl?.takeIf { it.isNotBlank() }?.let(::safeProviderUrl)
            ?: report?.activeDomain?.takeIf { it.isNotBlank() } ?: "Not resolved yet"))
        add("Connected · ${health.connected.text}" to "The provider responded and its catalog could be read.")
        add("Catalog · ${health.catalog.text}" to "Discovered listings; this does not guarantee stream availability.")
        add("Stream Fetching · ${health.fetching.text}" to when (health.fetching.text) {
            "Waiting for live" -> "Only upcoming fixtures were listed and no stream was available to verify. This is not a fetch failure. Test again when a match starts."
            "No listings" -> "No listings were available to sample. No stream fetch was verified; this does not establish a playback failure."
            "No stream available" -> "The sampled listings did not expose a testable media URL. The player may not be published yet; a fetch failure has not been established."
            "Fetch failed" -> "A media probe failed or returned no recognized media bytes. See the stream results below for the error."
            "Test failed" -> "The provider test could not complete. See the provider notice below; this is not necessarily a media fetch error."
            else -> "True requires fetched, recognized media bytes. A playlist URL alone is not enough. Video/audio decoding is not tested."
        })
        add("Last test" to (report?.let { "${java.time.Instant.ofEpochMilli(it.checkedAt)} · ${it.phase}" } ?: "Not tested yet"))
        listOfNotNull(status?.warning, status?.error, report?.warning, report?.error).filter { it.isNotBlank() }.distinct().forEach { add("Provider notice" to it) }
        report?.listings.orEmpty().forEach { listing ->
            add("Tested listing" to listing.title)
            if (listing.error.isNotBlank()) add("Listing result" to listing.error)
            listing.choices.forEach { choice ->
                add(choice.title to "${choice.stage} · ${choice.segments.size} segments · ${choice.segments.sumOf { it.bytes.toLong() }} bytes")
                add("Media URL (credentials hidden)" to choice.mediaUrl.ifBlank { "Not recorded; rerun test" })
                choice.segments.forEachIndexed { index, segment ->
                    add("Segment ${index + 1} · ${segment.format}" to "${segment.bytes} bytes · SHA-256 ${segment.sha256}")
                }
                if (choice.error.isNotBlank()) add("Stream result" to choice.error)
            }
        }
    }
}

fun RuntimeSnapshot.providerTestsBusy() = testBatch.running || reports.values.any {
    it.phase in listOf(ProbePhase.DISCOVERING, ProbePhase.RESOLVING, ProbePhase.FETCHING_SEGMENTS)
}
