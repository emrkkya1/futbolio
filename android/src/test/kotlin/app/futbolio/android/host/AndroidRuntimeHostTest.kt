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
    private class FakeEngine(private val gate: CompletableDeferred<Unit>? = null, private val fail: Boolean = false,
        override val host: HostConfig = HostConfig(), private val occupied: Boolean = false) : EngineControl {
        override val phase = MutableStateFlow(RuntimePhase.STOPPED)
        override val snapshot = MutableStateFlow(RuntimeSnapshot())
        override val providerStatuses = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val providerReports = MutableStateFlow<Map<String, ProviderTestReport>>(emptyMap())
        override val probeCandidates = MutableStateFlow<Map<String, List<ProbeCandidate>>>(emptyMap())
        var closes = 0
        override suspend fun start() {
            phase.value = RuntimePhase.STARTING
            gate?.await()
            if (occupied) throw java.net.BindException("Address already in use")
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
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _, _ -> FakeEngine().also { created += it } }
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
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _, _ -> engine }
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
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {}) { _, _, _, _ -> FakeEngine().also { created += it } }
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
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { desired }, { desired = it }, {}) { _, _, _, _ -> engine }
        host.start(host.claimOwner(), poster, MediaActivity.None) { stopped++ }
        runCurrent()
        assertFalse(desired)
        assertEquals(1, stopped)
        assertEquals(1, engine.closes)
        assertTrue(host.status.value.error.isNotBlank())
        assertFalse(host.status.value.error.contains("private-startup-error"))
    }

    @Test fun occupiedPortIsRetriedPersistedAndExposedToInstallationActions() = runTest {
        val created = mutableListOf<FakeEngine>()
        var savedPort = 7000
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { true }, {}, {},
            preferredPort = { savedPort }, portSelected = { savedPort = it }) { config, _, _, _ ->
            FakeEngine(host = config, occupied = config.port == 7000).also { created += it }
        }
        val owner = host.claimOwner()
        host.start(owner, poster, MediaActivity.None) {}
        runCurrent()
        assertTrue(host.status.value.running)
        assertEquals(7001, savedPort)
        assertEquals("http://127.0.0.1:7001/manifest.json", host.activeHost.manifest)
        assertEquals(1, created.first().closes)
        host.releaseOwner(owner)
        runCurrent()
        host.start(host.claimOwner(), poster, MediaActivity.None) {}
        runCurrent()
        assertEquals(7001, created.last().host.port)
        assertEquals(3, created.size)
    }

    @Test fun exhaustedPortRangeStopsCleanlyWithoutPersistingAFailedPort() = runTest {
        val created = mutableListOf<FakeEngine>()
        var desired = true
        var saved = false
        val host = AndroidRuntimeHost(backgroundScope, coordinator(), { desired }, { desired = it }, {},
            portSelected = { saved = true }) { config, _, _, _ ->
            FakeEngine(host = config, occupied = true).also { created += it }
        }
        host.start(host.claimOwner(), poster, MediaActivity.None) {}
        runCurrent()
        assertEquals(20, created.size)
        assertTrue(created.all { it.closes == 1 })
        assertFalse(saved)
        assertFalse(desired)
        assertFalse(host.status.value.running)
        assertTrue(host.status.value.error.isNotBlank())
    }
}
