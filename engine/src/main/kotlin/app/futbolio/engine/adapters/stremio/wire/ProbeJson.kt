package app.futbolio.engine.adapters.stremio.wire

import app.futbolio.application.api.*
import kotlinx.serialization.json.*

internal fun probeReportJson(report: ProviderTestReport) = buildJsonObject {
    put("providerId", report.providerId); put("providerName", report.providerName); put("checkedAt", report.checkedAt)
    put("phase", report.phase.wire); put("catalogCount", report.catalogCount); put("activeDomain", report.activeDomain)
    put("warning", report.warning); put("error", report.error)
    put("listings", JsonArray(report.listings.map { listing -> buildJsonObject {
        put("id", listing.id); put("title", listing.title); put("error", listing.error)
        put("choices", JsonArray(listing.choices.map { choice -> buildJsonObject {
            put("id", choice.id); put("title", choice.title); put("stage", choice.stage.wire)
            put("error", choice.error); put("playback", choice.playback.wire); put("playbackCheckedAt", choice.playbackCheckedAt)
            put("segments", JsonArray(choice.segments.map { segment -> buildJsonObject {
                put("bytes", segment.bytes); put("format", segment.format); put("sha256", segment.sha256)
            } }))
        } }))
    } }))
}

internal fun probeReportsJson(reports: Map<String, ProviderTestReport>) = buildJsonObject {
    reports.forEach { (id, report) -> put(id, probeReportJson(report)) }
}

internal fun probeCandidatesJson(candidates: Map<String, List<ProbeCandidate>>) = buildJsonObject {
    candidates.forEach { (id, entries) -> put(id, JsonArray(entries.map { entry -> buildJsonObject {
        put("id", entry.id); put("title", entry.title); put("league", entry.league); put("channel", entry.channel)
    } })) }
}
