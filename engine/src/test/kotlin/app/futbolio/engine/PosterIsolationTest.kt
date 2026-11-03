package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.engine.runtime.FutbolioEngine

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PosterIsolationTest {
    private fun status(url: String): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 6000
        try { val code = c.responseCode; if (code == 200) c.inputStream.use { it.readBytes() }; return code }
        finally { c.disconnect() }
    }
    @Test fun posterBurstCannotOccupyCatalogApiSlots() = runBlocking {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val fixture = FixtureUpstream()
        val renderer = PosterRenderer { _, _ ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            byteArrayOf(137.toByte(), 80, 78, 71)
        }
        val engine = FutbolioEngine(HostConfig(port = ServerSocket(0).use { it.localPort }), emptyList(), renderer, fixture)
        try {
            engine.start()
            val posters = (1..16).map { i -> async(Dispatchers.IO) { status("${engine.host.base}/poster/unknown-$i.png") } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            try {
                assertEquals(200, withTimeout(1500) { withContext(Dispatchers.IO) { status("${engine.host.base}/manifest.json") } })
                assertEquals(200, withTimeout(1500) { withContext(Dispatchers.IO) { status("${engine.host.base}/catalog/tv/football.json") } })
            } finally { release.countDown() }
            assertTrue(posters.awaitAll().all { it == 200 }, "Poster bursts must not return API-busy errors")
        } finally { release.countDown(); engine.close() }
    }
}
