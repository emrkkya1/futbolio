package app.futbolio.engine.api

import app.futbolio.application.api.ProviderTestReport
import app.futbolio.engine.adapters.stremio.wire.probeReportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ReportFormats {
    private val json = Json { prettyPrint = true }
    fun export(report: ProviderTestReport): String = json.encodeToString(JsonElement.serializer(), probeReportJson(report))
}

fun ProviderTestReport.export() = ReportFormats.export(this)
