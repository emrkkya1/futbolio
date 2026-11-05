package app.futbolio.android.updates

import app.futbolio.application.ports.readLimited
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Public GitHub requests only: no account, credentials, analytics, or provider URLs. */
internal class ReleaseClient {
    suspend fun latest(): String? {
        val connection = open(ReleasePolicy.ENDPOINT, "application/vnd.github+json")
        return try {
            if (connection.responseCode == 404) null else {
                check(connection.responseCode == 200) { "Could not check GitHub releases. Please try again later." }
                connection.inputStream.use { readLimited(it, 1024 * 1024).toString(Charsets.UTF_8) }
            }
        } finally { connection.disconnect() }
    }

    suspend fun download(release: GitHubRelease, destination: File, progress: (Int) -> Unit) {
        val connection = open(release.url, "application/octet-stream")
        try {
            check(connection.responseCode == 200) { "GitHub could not download the update. Please retry later." }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var lastPercent = -1
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= release.bytes) { "The update size did not match GitHub. Please retry later." }
                    hash.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    val percent = (bytes * 100 / release.bytes).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            ReleasePolicy.verifyDownload(release, bytes, hash.digest().joinToString("") { "%02x".format(it) })
        } finally { connection.disconnect() }
    }

    private fun open(url: String, accept: String): HttpsURLConnection {
        var next = URI(url)
        repeat(6) {
            check(next.scheme == "https" && next.userInfo == null && next.port in listOf(-1, 443) && next.host in setOf(
                "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            )) { "GitHub returned an unexpected download address." }
            val connection = (next.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Futbolio-Android-Updater")
                if (next.host == "api.github.com") setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                next = next.resolve(connection.getHeaderField("Location") ?: error("GitHub returned an invalid redirect."))
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        error("GitHub redirected the download too many times.")
    }
}
