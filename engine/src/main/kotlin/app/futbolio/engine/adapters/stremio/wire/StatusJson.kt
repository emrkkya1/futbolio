package app.futbolio.engine.adapters.stremio.wire

import app.futbolio.application.api.ProviderStatus
import kotlinx.serialization.json.*

internal fun providerStatusesJson(statuses: List<ProviderStatus>) = JsonArray(statuses.map { status ->
    buildJsonObject {
        put("id", status.id); put("name", status.name); put("enabled", status.enabled)
        put("loading", status.loading); put("ok", status.ok); put("count", status.count)
        put("updated", status.updated); put("checked", status.checked); put("error", status.error)
        put("warning", status.warning); put("activeUrl", status.activeUrl)
    }
})
