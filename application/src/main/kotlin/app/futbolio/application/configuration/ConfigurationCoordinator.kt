package app.futbolio.application.configuration

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes latest-state mutations and persistence; explicitly distinguishes saved from applied. */
class ConfigurationCoordinator(
    private val store: ConfigurationStore,
    initial: FutbolioConfig,
    private val validate: (FutbolioConfig) -> Unit,
) : ConfigurationController {
    private val lock = Mutex()
    private var target: ConfigurationTarget? = null
    private val mutableState = MutableStateFlow(ConfigurationState(initial))
    override val state = mutableState.asStateFlow()

    override suspend fun load() = lock.withLock {
        val loaded = store.read() ?: mutableState.value.config
        validate(loaded)
        val previous = mutableState.value
        mutableState.value = previous.copy(config = loaded, desiredRevision = previous.desiredRevision + if (loaded != previous.config) 1 else 0, applyError = "")
        applyCurrent()
    }

    override suspend fun attach(target: ConfigurationTarget?) = lock.withLock {
        this.target = target
        applyCurrent()
    }

    override suspend fun update(transform: (FutbolioConfig) -> FutbolioConfig): ConfigurationState = lock.withLock {
        val next = transform(mutableState.value.config)
        validate(next)
        store.write(next) // A failed save must not publish desired state or touch the running engine.
        val old = mutableState.value
        mutableState.value = old.copy(config = next, desiredRevision = old.desiredRevision + 1, applyError = "")
        applyCurrent()
        mutableState.value
    }

    private fun applyCurrent() {
        val current = mutableState.value
        val destination = target
        if (destination == null) {
            mutableState.value = current.copy(appliedRevision = null, applyError = "")
            return
        }
        try {
            destination.apply(current.config)
            mutableState.value = current.copy(appliedRevision = current.desiredRevision, applyError = "")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = current.copy(applyError = "Settings were saved but could not be applied. Stop and restart Futbolio.")
        }
    }
}
