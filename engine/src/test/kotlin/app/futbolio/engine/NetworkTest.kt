package app.futbolio.engine

import app.futbolio.application.ports.*

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.*

class NetworkTest {
    private fun publicOnly(address: InetAddress) { if (!AddressPolicy.isPublic(address)) throw UnknownHostException("Destination blocked") }
    private fun fixture(test: suspend (String) -> Unit) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); server.executor = executor
        server.createContext("/chunked") { ex -> ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write("hello chunked".toByteArray()) }; ex.close() }
        server.createContext("/redirect") { ex -> ex.responseHeaders.add("Location", "/chunked"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/unsafe") { ex -> ex.responseHeaders.add("Location", "file:///private"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/loop") { ex -> ex.responseHeaders.add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/gzip") { ex ->
            val out = java.io.ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(out).use { it.write("gzip body".toByteArray()) }
            val bytes = out.toByteArray(); ex.responseHeaders.add("Content-Encoding", "gzip"); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }; ex.close()
        }
        server.createContext("/large") { ex -> ex.sendResponseHeaders(200, 4096); ex.responseBody.use { it.write(ByteArray(4096)) }; ex.close() }
        server.start()
        try { test("http://127.0.0.1:${server.address.port}") } finally { server.stop(0); executor.shutdownNow() }
    }
    @Test fun decodesChunkedBodies() = fixture { base -> HttpUpstream.fixture().use { assertEquals("hello chunked", it.text("$base/chunked").text) } }
    @Test fun followsRelativeRedirects() = fixture { base -> HttpUpstream.fixture().use { assertTrue(it.text("$base/redirect").url.endsWith("/chunked")) } }
    @Test fun decodesGzip() = fixture { base -> HttpUpstream.fixture().use { assertEquals("gzip body", it.text("$base/gzip").text) } }
    @Test fun rejectsUnsafeRedirect() = fixture { base -> HttpUpstream.fixture().use { assertFails { it.text("$base/unsafe") }; Unit } }
    @Test fun redirectCountBounded() = fixture { base -> HttpUpstream.fixture().use { assertFails { it.text("$base/loop") }; Unit } }
    @Test fun bodyReadBounded() = fixture { base -> HttpUpstream.fixture().use { net -> net.open("$base/large").useResponse { assertFails { readLimited(it.body, 1024) }; Unit } } }
    @Test fun rejectsHeaderInjection() { assertFails { SourceHeaders("x\r\ny") }; assertFails { SourceHeaders(origin = "x\ny") } }
    @Test fun cancellationClosesBlockedResponse() = runBlocking {
        val closed = AtomicBoolean(false)
        val response = UpstreamResponse(200, "https://fixture.example/", emptyMap(), byteArrayOf().inputStream()) { closed.set(true) }
        val job = launch { response.useResponse { awaitCancellation() } }
        yield(); job.cancelAndJoin(); assertTrue(closed.get())
    }
    @Test fun strictPolicyBlocksLiteralAddressesBeforeConnecting() = fixture { base ->
        HttpUpstream.fixture(addressCheck = ::publicOnly).use { net ->
            assertFailsWith<UnknownHostException> { net.open("$base/chunked") }
        }
    }
    @Test fun mixedDnsAnswersAreRejectedAsOneValidatedSet() = runBlocking {
        var lookups = 0
        HttpUpstream.fixture(::publicOnly) {
            lookups++
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))
        }.use { net -> assertFailsWith<UnknownHostException> { net.open("http://fixture.example/") } }
        assertEquals(1, lookups)
    }
}
