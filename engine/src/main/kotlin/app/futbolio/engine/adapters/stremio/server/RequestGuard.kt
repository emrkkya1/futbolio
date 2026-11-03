package app.futbolio.engine.adapters.stremio.server

import app.futbolio.application.api.HostConfig
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*

internal class RequestGuard(private val host: HostConfig) {
    suspend fun path(call: ApplicationCall): String? {
        call.response.headers.apply {
            append(HttpHeaders.AccessControlAllowOrigin, "*")
            append(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
            append(HttpHeaders.AccessControlAllowHeaders, "Content-Type, Range")
            append(HttpHeaders.AccessControlExposeHeaders, "Content-Length, Content-Range, Accept-Ranges")
            append(HttpHeaders.CacheControl, "no-store")
            append("X-Content-Type-Options", "nosniff")
        }
        val authority = call.request.headers[HttpHeaders.Host].orEmpty().lowercase()
        val allowed = setOf("127.0.0.1:${host.port}", "localhost:${host.port}", "${host.advertisedHost}:${host.port}".lowercase())
        if (authority !in allowed) { text(call, 400, "Invalid local host"); return null }
        val method = call.request.httpMethod
        if (method !in listOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) {
            text(call, 405, "Only GET, HEAD and OPTIONS are supported"); return null
        }
        val raw = call.request.path()
        if (raw.length > 8192) { text(call, 414, "Request too long"); return null }
        if (host.prefix.isNotEmpty() && !raw.startsWith(host.prefix + "/")) { text(call, 404, "Not found"); return null }
        if (method == HttpMethod.Options) { text(call, 204, ""); return null }
        return raw.removePrefix(host.prefix)
    }
}
