package app.futbolio.engine.api

import app.futbolio.application.api.DiagnosticSnapshot
import app.futbolio.engine.adapters.stremio.wire.diagnosticJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object DiagnosticFormats {
    private val json = Json { prettyPrint = true }
    fun export(snapshot: DiagnosticSnapshot): String = json.encodeToString(JsonElement.serializer(), diagnosticJson(snapshot))
}
