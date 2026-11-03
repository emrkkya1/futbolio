package app.futbolio.engine

import kotlinx.coroutines.*
import okhttp3.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import app.futbolio.application.ports.*

object AddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }; val a = b[0]
        if (b.size == 4) return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 169 && b[1] == 254) || (a == 172 && b[1] in 16..31) ||
            (a == 192 && (b[1] in listOf(0, 168) || (b[1] == 88 && b[2] == 99))) ||
            (a == 100 && b[1] in 64..127) || (a == 198 && b[1] in 18..19) ||
            (a == 198 && b[1] == 51 && b[2] == 100) || (a == 203 && b[1] == 0 && b[2] == 113))
        return a in 0x20..0x3f && !(a == 0x20 && b[1] == 1 && ((b[2] == 0 && b[3] == 0) || (b[2] == 0x0d && b[3] == 0xb8)))
    }
}

/** DNS results are validated, then OkHttp connects to those exact results (no second DNS lookup). */
class HttpUpstream private constructor(
    private val addressCheck: (InetAddress) -> Unit,
    private val fixture: Boolean = false,
    private val lookup: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
) : Upstream {
    constructor() : this({ if (!AddressPolicy.isPublic(it)) throw java.net.UnknownHostException("Private network sources are blocked") })
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
        /** Test-only constructor; never exposed through user configuration. */
        internal fun fixture(addressCheck: (InetAddress) -> Unit = {}, lookup: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) =
            HttpUpstream(addressCheck, fixture = true, lookup)
    }
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns { host -> lookup(host).also { addresses -> addresses.forEach(addressCheck) } }
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build()
    override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = request(url, headers, range, null)
    override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders): UpstreamResponse {
        require(fields.size == 1 && fields.keys.single() in listOf("id", "channel") && fields.values.single().matches(Regex("[0-9]{1,20}"))) { "Unsupported player request" }
        return request(url, headers, null, fields)
    }
    private suspend fun request(url: String, headers: SourceHeaders, range: String?, fields: Map<String, String>?): UpstreamResponse {
        // AssertionError cannot be swallowed as a provider outage by catch(Exception).
        if (!fixture && java.lang.Boolean.getBoolean("futbolio.offlineTests")) {
            throw AssertionError("Production networking is forbidden in deterministic tests; inject a fixture transport")
        }
        require(range == null || (range.matches(Regex("bytes=\\d*-\\d*")) && range != "bytes=-")) { "Unsupported byte range" }
        var target = Web.url(url).toString()
        var form = fields
        repeat(6) { redirect ->
            // OkHttp bypasses Dns for literal IP hosts, so validate those explicitly.
            // This keeps the same SSRF policy for direct URLs and DNS-resolved URLs.
            Web.url(target).host.let { host ->
                if (host.matches(Regex("\\d+(\\.\\d+){3}")) || host.contains(':')) {
                    addressCheck(InetAddress.getByName(host))
                }
            }
            val request = Request.Builder().url(target)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "*")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("sec-ch-ua", "\"Not(A):Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-ch-ua-platform-version", "\"15.0.0\"")
            if (headers.referer.isNotEmpty()) request.header("Referer", headers.referer)
            if (headers.origin.isNotEmpty()) {
                request.header("Origin", headers.origin)
            }
            if (range != null) request.header("Range", range)
            headers.extra.forEach { (key, value) -> request.header(key, value) }
            headers.scopedAuthFor(target)?.let { request.header("X-CSRF-Auth", it) }
            form?.let { values -> request.post(FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()) }
            val response = client.newCall(request.build()).await()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location"); response.close()
                if (redirect == 5 || location == null) throw IOException("Source redirect limit")
                if (response.code in listOf(301, 302, 303)) form = null
                target = Web.resolve(target, location)
            } else {
                val body = response.body
                return UpstreamResponse(response.code, response.request.url.toString(),
                    response.headers.names().associate { it.lowercase() to response.header(it).orEmpty() }, body.byteStream(), response::close)
            }
        }
        error("Source redirect limit")
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }
}
