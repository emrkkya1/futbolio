package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.configuration.ConfigurationCoordinator
import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConfigurationCoordinatorTest {
    private class Store : ConfigurationStore {
        var value: FutbolioConfig? = null
        var fail = false
        override suspend fun read() = value
        override suspend fun write(config: FutbolioConfig) { delay(1); check(!fail) { "private-storage-error" }; value = config }
    }
    private fun coordinator(store: Store) = ConfigurationCoordinator(store, FutbolioConfig()) { config ->
        require(config.schema == 1)
        config.sources.forEach { it.validateShape() }
    }

    @Test fun failedPersistenceDoesNotPublishOrApply() = runTest {
        val store = Store()
        val coordinator = coordinator(store)
        var applied = 0
        coordinator.attach(ConfigurationTarget { applied++ })
        val before = coordinator.state.value
        store.fail = true
        assertFails { coordinator.update { it.copy(presentation = it.presentation.copy(displayTimeZone = "UTC")) } }
        assertEquals(before, coordinator.state.value)
        assertEquals(1, applied)
        assertNull(store.value)
    }

    @Test fun savedButUnappliedSettingsRemainExplicitAndReconcileOnRestart() = runTest {
        val store = Store()
        val coordinator = coordinator(store)
        var fail = false
        coordinator.attach(ConfigurationTarget { if (fail) error("private-engine-error") })
        fail = true
        val changed = coordinator.update { it.copy(presentation = it.presentation.copy(displayTimeZone = "UTC")) }
        assertEquals(changed.config, store.value)
        assertEquals(1, changed.desiredRevision)
        assertEquals(0, changed.appliedRevision)
        assertTrue(changed.applyError.contains("saved"))
        assertFalse(changed.applyError.contains("private-engine-error"))
        coordinator.attach(null)
        assertNull(coordinator.state.value.appliedRevision)
        coordinator.attach(ConfigurationTarget {})
        assertEquals(1, coordinator.state.value.appliedRevision)
        assertTrue(coordinator.state.value.applyError.isBlank())
    }

    @Test fun concurrentEditsAreBasedOnLatestStateNotCapturedCopies() = runTest {
        val coordinator = coordinator(Store())
        val sources = listOf("one", "two").map { SourceConfig(it, "fixture", it, "https://fixture.example/") }
        sources.map { source -> async { coordinator.update { it.copy(sources = it.sources + source) } } }.awaitAll()
        assertEquals(setOf("one", "two"), coordinator.state.value.config.sources.map { it.id }.toSet())
        assertEquals(2, coordinator.state.value.desiredRevision)
    }
}
