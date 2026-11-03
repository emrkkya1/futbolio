package app.futbolio.domain.provider

import java.net.URI

/** Syntactic HTTP(S) URL policy. Network destination validation belongs to transport. */
object SourceUrl {
    fun origin(value: String) = parse(value).let { "${it.scheme}://${it.rawAuthority}" }
    fun parse(value: String): URI {
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid source URL") }
        require(value.length <= 8192 && uri.host != null && uri.rawUserInfo == null && uri.fragment == null &&
            uri.scheme.lowercase() in listOf("http", "https") && (uri.port == -1 || uri.port in 1..65535)) {
            "Use an HTTP(S) URL without credentials or fragments"
        }
        return uri
    }
}
