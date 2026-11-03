package app.futbolio.application.catalog

import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.provider.SourceUrl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded, explicit provider recovery. No TLD guessing, unrestricted scanning or certificate bypass. */
class DomainRecovery(private val net: Upstream, private val providers: ProviderDirectory) {
    private val successful = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun candidates(source: SourceConfig): List<String> {
        val numbered = mutableListOf<String>()
        val uri = SourceUrl.parse(source.url)
        val match = Regex("^(.*?)(\\d+)(\\.[a-zA-Z.]+)$").matchEntire(uri.host)
        if (match != null && source.numberedDomainWindow > 0) {
            val number = match.groupValues[2].toIntOrNull()
            if (number != null) for (delta in 1..source.numberedDomainWindow) for (n in listOf(number + delta, number - delta)) {
                if (n < 0) continue
                val host = match.groupValues[1] + n + match.groupValues[3]
                numbered += java.net.URI(uri.scheme, null, host, uri.port, uri.path, uri.query, null).toString()
            }
        }
        return (listOfNotNull(successful[source.id], source.url) + source.fallbackUrls + numbered).distinct().take(6)
    }
    suspend fun discover(source: SourceConfig): ProviderResult {
        val epoch = generation.get()
        var last: Exception = java.io.IOException("Source unavailable")
        for (url in candidates(source)) try {
            val configured = source.copy(url = url)
            val result = withTimeout(9_000) { providers.get(source.kind).discover(configured, net) }
            require(result.listings.isNotEmpty()) { "No compatible provider listings" }
            if (epoch == generation.get()) successful[source.id] = url
            return result.copy(activeUrl = result.activeUrl.ifBlank { url })
        } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); last = java.net.SocketTimeoutException() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { last = e }
        try { providers.get(source.kind).recover(source, net)?.let { return it } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
        throw last
    }
    fun reset() { generation.incrementAndGet(); successful.clear() }
}
