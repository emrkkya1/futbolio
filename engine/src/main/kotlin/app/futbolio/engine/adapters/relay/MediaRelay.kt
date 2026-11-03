package app.futbolio.engine.adapters.relay

import app.futbolio.application.api.MediaActivity
import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.*
import app.futbolio.engine.adapters.diagnostics.Diagnostics
import kotlinx.coroutines.*
import java.io.OutputStream
import java.io.PushbackInputStream

/** Body leases cover the entire callback. The HTTP adapter must consume the writer before returning. */
internal data class RelayResponse(
    val status: Int,
    val contentType: String,
    val length: Long?,
    val headers: Map<String, String> = emptyMap(),
    val write: suspend (OutputStream) -> Unit,
)

internal class MediaRelay(
    private val net: Upstream,
    private val tokens: RelayTokens,
    private val diagnostics: Diagnostics,
    private val activity: MediaActivity,
    private val limits: ResourceLimits,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun message(status: Int, message: String, respond: suspend (RelayResponse) -> Unit) {
        val bytes = message.toByteArray()
        respond(RelayResponse(status, "text/plain; charset=UTF-8", bytes.size.toLong(), write = { it.write(bytes) }))
    }

    suspend fun serve(token: String, range: String?, respond: suspend (RelayResponse) -> Unit) {
        val entry = tokens.get(token) ?: return message(404, "Stream link expired. Select the stream again.", respond)
        if (range != null && (!range.matches(Regex("bytes=\\d*-\\d*")) || range == "bytes=-"))
            return message(416, "Only a single byte range is supported", respond)
        diagnostics.activeMedia.incrementAndGet()
        diagnostics.mediaRequests.incrementAndGet()
        try {
            activity.started()
            (entry.session?.open(entry.url, if (entry.playlist) null else range)
                ?: net.open(entry.url, entry.headers, if (entry.playlist) null else range)).useResponse { response ->
                if (response.status !in 200..299) {
                    diagnostics.failures.incrementAndGet()
                    return message(502, "Source unavailable (HTTP ${response.status}). Try another source or the website.", respond)
                }
                val input = PushbackInputStream(response.body, 512)
                val prefix = withContext(io) {
                    val bytes = ByteArray(512)
                    var count = 0
                    while (count < bytes.size) {
                        ensureActive()
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                        if (entry.playlist && count >= 16) break
                    }
                    input.unread(bytes, 0, count)
                    bytes.copyOf(count)
                }
                if (prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) {
                    val playlist = readLimited(input, limits.playlistBytes, io).toString(Charsets.UTF_8)
                    val bytes = Hls.rewrite(playlist, response.url) { tokens.register(it, entry.headers, session = entry.session) }.toByteArray()
                    return respond(RelayResponse(200, "application/vnd.apple.mpegurl", bytes.size.toLong(), write = { it.write(bytes) }))
                }
                check(!entry.playlist) { "Source did not return HLS" }
                val type = when {
                    prefix.size > 376 && listOf(0, 188, 376).all { prefix[it] == 0x47.toByte() } -> "video/mp2t"
                    prefix.size > 8 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) in listOf("ftyp", "styp", "moof") -> "video/mp4"
                    Regex("\\.ts(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(entry.url) -> "video/mp2t"
                    else -> response.header("content-type").ifBlank { "application/octet-stream" }
                }
                val length = response.header("content-length").toLongOrNull()
                require(length == null || length in 0..limits.mediaObjectBytes) { "Media object too large" }
                val headers = listOf("content-range", "accept-ranges").mapNotNull { name -> response.header(name).takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
                respond(RelayResponse(response.status, type, length, headers) { output ->
                    withContext(io) {
                        val buffer = ByteArray(limits.mediaBufferBytes)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= limits.mediaObjectBytes) { "Media object too large" }
                            output.write(buffer, 0, count)
                            diagnostics.mediaBytes.addAndGet(count.toLong())
                            activity.progress()
                        }
                    }
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            diagnostics.failures.incrementAndGet()
            diagnostics.event("Media transfer failed; no source URL logged")
            throw failure // Only the HTTP adapter knows whether its response is already committed.
        } finally {
            diagnostics.activeMedia.decrementAndGet()
            activity.finished()
        }
    }
}
