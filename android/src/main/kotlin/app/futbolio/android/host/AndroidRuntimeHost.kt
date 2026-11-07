package app.futbolio.android.host

import app.futbolio.application.api.*
import app.futbolio.engine.api.EngineControl
import app.futbolio.engine.api.startWithPortFallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Process-owned, testable service bridge. Only immutable status leaves this host. */
class AndroidRuntimeHost(
    private val scope: CoroutineScope,
    private val configuration: ConfigurationController,
    private val desired: suspend () -> Boolean,
    private val setDesired: suspend (Boolean) -> Unit,
    private val ready: suspend () -> Unit,
    private val preferredPort: suspend () -> Int = { 7000 },
    private val portSelected: suspend (Int) -> Unit = {},
    private val create: (HostConfig, FutbolioConfig, PosterRenderer, MediaActivity) -> EngineControl,
) {
    private data class Owned(val owner: Long, val engine: EngineControl, val observer: Job)
    private val sequence = AtomicLong()
    private val owner = AtomicLong()
    private val lifecycle = Mutex()
    @Volatile private var running: Owned? = null
    private var startup: Job? = null
    private var startupOwner = 0L
    private val mutableStatus = MutableStateFlow(RuntimeSnapshot())
    val status = mutableStatus.asStateFlow()
    @Volatile var activeHost = HostConfig()
        private set

    fun claimOwner(): Long = sequence.incrementAndGet().also { owner.set(it) }

    @Synchronized fun start(id: Long, poster: PosterRenderer, activity: MediaActivity, stopService: () -> Unit) {
        if (startup?.isActive == true && startupOwner == id) return
        startup?.cancel()
        startupOwner = id
        startup = scope.launch {
            lifecycle.withLock {
                if (owner.get() != id) return@withLock
                if (running?.owner == id) return@withLock
                var created: EngineControl? = null
                try {
                    ready()
                    stopCurrent()
                    if (!desired()) { stopService(); return@withLock }
                    mutableStatus.value = RuntimeSnapshot(RuntimePhase.STARTING)
                    val engine = startWithPortFallback(HostConfig(port = preferredPort())) {
                        create(it, configuration.state.value.config, poster, activity)
                    }
                    created = engine
                    portSelected(engine.host.port)
                    activeHost = engine.host
                    if (owner.get() != id || !desired()) { engine.shutdown(); return@withLock }
                    configuration.attach(ConfigurationTarget { engine.configure(it.sources, it.presentation) })
                    if (owner.get() != id) { configuration.attach(null); engine.shutdown(); return@withLock }
                    mutableStatus.value = RuntimeSnapshot(RuntimePhase.RUNNING, engine.providerStatuses.value, engine.providerReports.value,
                        engine.probeCandidates.value, engine.diagnosticSnapshot())
                    val observer = scope.launch(start = CoroutineStart.LAZY) {
                        engine.snapshot.collect { snapshot ->
                            if (owner.get() == id && running?.engine === engine) mutableStatus.value = snapshot.copy(phase = engine.phase.value)
                        }
                    }
                    running = Owned(id, engine, observer)
                    observer.start()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { created?.shutdown() }
                    throw cancelled
                } catch (_: Exception) {
                    created?.shutdown()
                    runCatching { setDesired(false) }
                    if (owner.get() == id) mutableStatus.value = RuntimeSnapshot(error = "Could not start. Check provider settings and available network ports.")
                    stopService()
                }
            }
        }
    }

    fun releaseOwner(id: Long) {
        owner.compareAndSet(id, 0)
        synchronized(this) { if (startupOwner == id) startup?.cancel() }
        if (running?.owner == id) mutableStatus.value = mutableStatus.value.copy(phase = RuntimePhase.STOPPING)
        scope.launch {
            lifecycle.withLock {
                if (running?.owner == id) stopCurrent()
                else if (owner.get() == 0L) mutableStatus.value = RuntimeSnapshot(error = mutableStatus.value.error)
            }
        }
    }

    private suspend fun stopCurrent() {
        val old = running
        running = null
        old?.observer?.cancel()
        configuration.attach(null)
        try { old?.engine?.shutdown() }
        finally { mutableStatus.value = RuntimeSnapshot(error = mutableStatus.value.error) }
    }

    fun markStopping() { if (mutableStatus.value.phase in listOf(RuntimePhase.RUNNING, RuntimePhase.STARTING)) mutableStatus.value = mutableStatus.value.copy(phase = RuntimePhase.STOPPING) }
    fun reportFailure(message: String) { mutableStatus.value = RuntimeSnapshot(error = message) }
    fun trim() { running?.engine?.trim() }
    fun refresh() { running?.engine?.refreshProviders() }
    fun test(sourceId: String, listingId: String?) { running?.engine?.testProvider(sourceId, listingId) }
    fun testAll() { running?.engine?.testAllProviders() }
    fun confirm(sourceId: String, choiceId: String, works: Boolean) { running?.engine?.confirmPlayback(sourceId, choiceId, works) }
}
