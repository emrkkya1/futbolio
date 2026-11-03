package app.futbolio.engine.adapters.stremio.wire

import app.futbolio.application.api.DiagnosticSnapshot
import kotlinx.serialization.json.*

internal fun diagnosticJson(snapshot: DiagnosticSnapshot) = buildJsonObject {
    put("activeMedia", snapshot.activeMedia); put("mediaBytes", snapshot.mediaBytes)
    put("mediaRequests", snapshot.mediaRequests); put("failures", snapshot.failures)
    put("events", JsonArray(snapshot.events.map(::JsonPrimitive)))
}
