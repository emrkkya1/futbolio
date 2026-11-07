package app.futbolio.application.ports

import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

data class Document(val url: String, val text: String) {
    override fun toString() = "Document(contents=[redacted])"
}

class UpstreamResponse(
    val status: Int, val url: String, val headers: Map<String, String>, val body: InputStream,
    private val cancelRequest: (() -> Unit)? = null,
    private val dispose: () -> Unit = { body.close() },
) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    fun header(name: String) = headers[name.lowercase()].orEmpty()
    override fun close() { if (closed.compareAndSet(false, true)) dispose() }
    fun abort() { if (cancelRequest != null) cancelRequest.invoke() else close() }
}

/** Validated transport supplied by composition; no vendor HTTP types cross this port. */
interface Upstream : Closeable {
    suspend fun open(url: String, headers: SourceHeaders = SourceHeaders(), range: String? = null): UpstreamResponse
    suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders): UpstreamResponse =
        throw UnsupportedOperationException("Provider player POST is unavailable")

    suspend fun text(url: String, headers: SourceHeaders = SourceHeaders()): Document = open(url, headers).useResponse {
        if (it.status !in 200..299) throw IOException("Upstream HTTP ${it.status}")
        Document(it.url, readLimited(it.body, 2 * 1024 * 1024).toString(Charsets.UTF_8))
    }

    override fun close() {}
}

/** Cancellation closes the leased body even while a blocking stream read is in progress. */
suspend inline fun <T> UpstreamResponse.useResponse(block: (UpstreamResponse) -> T): T {
    val lease = Job(currentCoroutineContext()[Job])
    lease.invokeOnCompletion { cause -> if (cause != null) abort() else close() }
    try {
        currentCoroutineContext().ensureActive()
        return block(this)
    } finally { lease.complete(); close() }
}

suspend fun readLimited(input: InputStream, limit: Int, dispatcher: CoroutineDispatcher = Dispatchers.IO): ByteArray = withContext(dispatcher) {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "Source response exceeds size limit" }
        out.write(buffer, 0, count)
    }
    out.toByteArray()
}
