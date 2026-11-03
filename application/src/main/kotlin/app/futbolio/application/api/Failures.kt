package app.futbolio.application.api

/** Safe boundary messages; never forwards arbitrary upstream exception text. */
object Failures {
    fun describe(error: Exception): String = when {
        error.message?.matches(Regex("Upstream HTTP [0-9]{3}")) == true -> error.message!!
        error is java.net.SocketTimeoutException -> "Source timed out"
        error is java.net.UnknownHostException -> "DNS lookup failed or destination blocked"
        error is javax.net.ssl.SSLException -> "TLS connection failed; check device clock and trust store"
        error is java.net.ConnectException -> "Could not connect to source"
        else -> "Source unavailable or layout changed (${error.javaClass.simpleName})"
    }
}
