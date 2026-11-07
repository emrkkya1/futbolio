package app.futbolio.application

import app.futbolio.application.ports.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ResponseLeaseTest {
    @Test fun cancellationAbortsTransportBeforeClosingBodyOnItsReaderThread() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val activeReads = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int {
                activeReads.incrementAndGet()
                reading.countDown()
                try { check(release.await(3, TimeUnit.SECONDS)); return -1 }
                finally { activeReads.decrementAndGet() }
            }
            override fun close() { assertEquals(0, activeReads.get()); closes.incrementAndGet() }
        }
        val response = UpstreamResponse(200, "https://fixture.example/", emptyMap(), stream,
            cancelRequest = { release.countDown() })
        val task = launch(Dispatchers.IO) { response.useResponse { it.body.read() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { reading.await(2, TimeUnit.SECONDS) })
            task.cancelAndJoin()
            assertEquals(1, closes.get())
        } finally { release.countDown(); task.cancelAndJoin() }
    }

    @Test fun cancellationUnblocksAnActualBlockingReadAndDisposesExactlyOnce() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Cancellation did not close the blocked body" }
                return -1
            }
            override fun close() { closes.incrementAndGet(); release.countDown() }
        }
        val response = UpstreamResponse(200, "https://fixture.example/", emptyMap(), stream)
        val task = launch(Dispatchers.IO) { response.useResponse { it.body.read() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { reading.await(2, TimeUnit.SECONDS) })
            task.cancel()
            withTimeout(2000) { task.join() }
            response.close()
            assertEquals(1, closes.get())
        } finally { release.countDown(); task.cancelAndJoin() }
    }
}
