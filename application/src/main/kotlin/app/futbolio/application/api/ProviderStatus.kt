package app.futbolio.application.api

/** Immutable status for presentation and protocol adapters. Timestamps are wall time. */
data class ProviderStatus(
    val id: String, val name: String, val enabled: Boolean, val loading: Boolean = false,
    val ok: Boolean = false, val count: Int = 0, val updated: Long = 0,
    val checked: Long = 0, val error: String = "", val warning: String = "", val activeUrl: String = "",
)
