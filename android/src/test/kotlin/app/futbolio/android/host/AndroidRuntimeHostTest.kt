package app.futbolio.android.host

import app.futbolio.application.api.*
import app.futbolio.application.configuration.ConfigurationCoordinator
import app.futbolio.application.ports.*
import app.futbolio.engine.api.EngineControl
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidRuntimeHostTest {
    private class FakeEngine(private val gate: CompletableDeferred<Unit>? = null, private val fail: Boolean = false) : EngineControl {
        override val host = HostConfig()
        override val phase = MutableStateFlow(RuntimePhase.STOPPED)
        override val snapshot = MutableStateFlow(RuntimeSnapshot())
        override val providerStatuses = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val providerReports = MutableStateFlow<Map<String, ProviderTestReport>>(emptyMap())
        override val probeCandidates = MutableStateFlow<Map<String, List<ProbeCandidate>>>(emptyMap())
        var closes = 0
        override suspend fun start() {
            phase.value = RuntimePhase.STARTING
            gate?.await()
            check(!fail) { "private-startup-error" }
            phase.value = RuntimePhase.RUNNING
            snapshot.value = RuntimeSnapshot(RuntimePhase.RUNNING)
        }
        override suspend fun shutdown() { closes++; phase.value = RuntimePhase.DISPOSED }
        override fun close() = error("Host teardown must use suspending shutdown")
        override fun configure(sources: List<SourceConfig>, presentation: PresentationConfig) {}
        override fun refreshProviders() {}
        override fun testProvider(sourceId: String, listingId: String?) {}
        override fun testAllProviders() {}
        override fun confirmPlayback(sourceId: String, choiceId: String, works: Boolean) {}
        override fun diagnosticSnapshot() = DiagnosticSnapshot(0, 0, 0, 0, emptyList())
        override fun trim() {}
    }
    private fun coordinator() = ConfigurationCoordinator(object : ConfigurationStore {
        override suspend fun read(): FutbolioConfig? = null
        override suspend fun write(config: FutbolioConfig) {}
    }, FutbolioConfig()) {}
    private val poster = PosterRenderer { _, _ -> byteArrayOf() }

    @Test fun duplicateStartIsIdempotentAndClosingActivityIsNotAnEngineStop() = runTest {
        val created = mutableListOf<FakeEngine>()
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _ -> FakeEngine().also { created += it } }
        val owner = host.claimOwner()
        host.start(owner, poster, MediaActivity.None) {}
        host.start(owner, poster, MediaActivity.None) {}
        runCurrent()
        assertEquals(1, created.size)
        assertTrue(host.status.value.running)
        // No Activity reference/lifecycle is part of this bridge; only the Service owns this token.
        host.releaseOwner(owner)
        runCurrent()
        assertEquals(1, created.single().closes)
        assertEquals(RuntimePhase.STOPPED, host.status.value.phase)
    }

    @Test fun stopDuringStartupDisposesPartialEngineAndNeverPublishesRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate)
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _ -> engine }
        val owner = host.claimOwner()
        host.start(owner, poster, MediaActivity.None) {}
        runCurrent()
        assertEquals(RuntimePhase.STARTING, host.status.value.phase)
        host.releaseOwner(owner)
        runCurrent()
        assertEquals(1, engine.closes)
        assertEquals(RuntimePhase.STOPPED, host.status.value.phase)
    }

    @Test fun oldServiceDestructionCannotStopANewerRuntime() = runTest {
        val created = mutableListOf<FakeEngine>()
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _ -> FakeEngine().also { created += it } }
        val old = host.claimOwner()
        host.start(old, poster, MediaActivity.None) {}
        runCurrent()
        val newer = host.claimOwner()
        host.start(newer, poster, MediaActivity.None) {}
        runCurrent()
        host.releaseOwner(old)
        runCurrent()
        assertTrue(host.status.value.running)
        assertEquals(1, created.first().closes)
        assertEquals(0, created.last().closes)
        host.releaseOwner(newer)
        runCurrent()
    }

    @Test fun failedStartupClearsDesiredStateAndPublishesOnlySafeError() = runTest {
        var desired = true
        var stopped = 0
        val engine = FakeEngine(fail = true)
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { desired }, { desired = it }, {}) { _, _, _ -> engine }
        host.start(host.claimOwner(), poster, MediaActivity.None) { stopped++ }
        runCurrent()
        assertFalse(desired)
        assertEquals(1, stopped)
        assertEquals(1, engine.closes)
        assertTrue(host.status.value.error.isNotBlank())
        assertFalse(host.status.value.error.contains("private-startup-error"))
    }
}
