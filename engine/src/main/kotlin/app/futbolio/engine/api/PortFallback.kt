package app.futbolio.engine.api

import app.futbolio.application.api.HostConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.net.BindException

/** Retry actual binds, not a racy free-port check. Every failed runtime is disposed. */
suspend fun startWithPortFallback(preferred: HostConfig, create: (HostConfig) -> EngineControl): EngineControl {
    val ports = (preferred.port..minOf(preferred.port + 19, 65535)).toList()
    for ((index, port) in ports.withIndex()) {
        currentCoroutineContext().ensureActive()
        val engine = create(preferred.copy(port = port))
        try {
            engine.start()
            return engine
        } catch (failure: Throwable) {
            withContext(NonCancellable) { engine.shutdown() }
            currentCoroutineContext().ensureActive()
            val occupied = generateSequence(failure) { it.cause }.any { it is BindException }
            if (!occupied || index == ports.lastIndex) throw failure
        }
    }
    error("No available port")
}
