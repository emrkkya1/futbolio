package app.futbolio.engine.adapters.diagnostics

import app.futbolio.application.api.DiagnosticSnapshot
import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.EventSink
import app.futbolio.application.ports.TimeSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class Diagnostics(private val time: TimeSource = TimeSource.System, private val limits: ResourceLimits = ResourceLimits()) : EventSink {
    val activeMedia = AtomicInteger()
    val mediaBytes = AtomicLong()
    val mediaRequests = AtomicLong()
    val failures = AtomicLong()
    private val events = ArrayDeque<String>()
    @Synchronized override fun event(message: String) {
        if (events.size >= limits.diagnosticEvents) events.removeFirst()
        events.addLast("${Instant.ofEpochMilli(time.wallMillis())} $message")
    }
    @Synchronized fun snapshot() = DiagnosticSnapshot(activeMedia.get(), mediaBytes.get(), mediaRequests.get(), failures.get(), events.toList())
}
