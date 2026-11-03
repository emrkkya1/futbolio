package app.futbolio.desktop

import app.futbolio.engine.*
import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.EngineControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class EngineBoundaryTest {
    private class FakeEngine(override val host: HostConfig, private val failStart: Boolean = false) : EngineControl {
        override val phase = MutableStateFlow(RuntimePhase.STOPPED)
        override val snapshot = MutableStateFlow(RuntimeSnapshot())
        override val providerStatuses = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val providerReports = MutableStateFlow<Map<String, ProviderTestReport>>(emptyMap())
        override val probeCandidates = MutableStateFlow<Map<String, List<ProbeCandidate>>>(emptyMap())
        var starts = 0
        var closes = 0
        var applied: List<SourceConfig>? = null
        override suspend fun start() { starts++; check(!failStart) { "Fixture failure" }; phase.value = RuntimePhase.RUNNING; snapshot.value = RuntimeSnapshot(RuntimePhase.RUNNING) }
        override fun configure(sources: List<SourceConfig>, presentation: PresentationConfig) { applied = sources }
        override fun refreshProviders() {}
        override fun testProvider(sourceId: String, listingId: String?) {}
        override fun testAllProviders() {}
        override fun confirmPlayback(sourceId: String, choiceId: String, works: Boolean) {}
        override fun diagnosticSnapshot() = DiagnosticSnapshot(0, 0, 0, 0, emptyList())
        override fun trim() {}
        override fun close() { closes++; phase.value = RuntimePhase.DISPOSED; snapshot.value = RuntimeSnapshot(RuntimePhase.DISPOSED) }
    }

    @Test fun hostLifecycleAndSettingsUseOnlyTheEngineContract() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val host = DesktopHost(Files.createTempDirectory("futbolio-api-host")) { config, _, _ ->
            FakeEngine(config).also { created += it }
        }
        try {
            host.start()
            host.start()
            assertEquals(1, created.size)
            assertEquals(1, created.single().starts)
            val config = FutbolioConfig(sources = emptyList())
            host.saveConfig(config)
            assertEquals(emptyList(), created.single().applied)
            host.stop()
            assertEquals(1, created.single().closes)
            host.start()
            assertEquals(2, created.size, "Host restart creates a fresh runtime")
        } finally { host.close() }
    }

    @Test fun failedStartupDisposesThePartialRuntimeAndPublishesSafeError() = runBlocking {
        var engine: FakeEngine? = null
        val host = DesktopHost(Files.createTempDirectory("futbolio-api-failure")) { config, _, _ ->
            FakeEngine(config, failStart = true).also { engine = it }
        }
        try {
            host.start()
            assertEquals(RuntimePhase.STOPPED, host.runtime.value.phase)
            assertEquals(1, engine?.closes)
            assertTrue(host.error.value.isNotBlank())
            assertFalse(host.error.value.contains("Fixture failure"))
        } finally { host.close() }
    }
}
