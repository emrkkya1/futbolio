package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.engine.api.*
import app.futbolio.engine.runtime.FutbolioEngine
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*

class PortFallbackTest {
    @Test fun occupiedPortUsesAnotherAndAdvertisesTheActualEndpoint() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val preferred = HostConfig(port = occupied.localPort)
            val attempts = mutableListOf<FutbolioEngine>()
            val engine = startWithPortFallback(preferred) { host ->
                FutbolioEngine(host, emptyList(), PosterRenderer { _, _ -> byteArrayOf() }).also { attempts += it }
            }
            try {
                assertTrue(engine.host.port > preferred.port)
                assertEquals(RuntimePhase.DISPOSED, attempts.first().phase.value)
                val response = URI(engine.host.manifest).toURL().readText()
                assertContains(response, "Futbolio")
                assertEquals(RuntimePhase.RUNNING, engine.phase.value)
            } finally { engine.shutdown() }
        }
    }

    @Test fun unrelatedStartupFailureIsNotRetried() = runBlocking {
        var attempts = 0
        assertFailsWith<IllegalArgumentException> {
            startWithPortFallback(HostConfig()) { attempts++; throw IllegalArgumentException("Invalid configuration") }
        }
        assertEquals(1, attempts)
    }
}
