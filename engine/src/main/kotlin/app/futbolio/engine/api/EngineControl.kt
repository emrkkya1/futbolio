package app.futbolio.engine.api

import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.flow.StateFlow

/** Host-facing control surface. No transport, caches, token registry or mutable counters. */
interface EngineControl : AutoCloseable {
    val host: HostConfig
    val phase: StateFlow<RuntimePhase>
    val snapshot: StateFlow<RuntimeSnapshot>
    val providerStatuses: StateFlow<List<ProviderStatus>>
    val providerReports: StateFlow<Map<String, ProviderTestReport>>
    val probeCandidates: StateFlow<Map<String, List<ProbeCandidate>>>

    suspend fun start()
    suspend fun shutdown() { close() }
    fun configure(sources: List<SourceConfig>, presentation: PresentationConfig)
    fun refreshProviders()
    fun testProvider(sourceId: String, listingId: String? = null)
    fun testAllProviders()
    fun confirmPlayback(sourceId: String, choiceId: String, works: Boolean)
    fun diagnosticSnapshot(): DiagnosticSnapshot
    fun trim()
}
