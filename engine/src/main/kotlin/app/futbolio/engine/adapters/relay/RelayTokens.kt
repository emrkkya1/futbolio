package app.futbolio.engine.adapters.relay

import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.*
import app.futbolio.engine.Web
import app.futbolio.engine.hash
import app.futbolio.engine.opaqueToken

internal class RelayTokens(
    private val base: String,
    private val limits: ResourceLimits = ResourceLimits(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Entry(val url: String, val headers: SourceHeaders, val identity: String, var expires: Long, val playlist: Boolean, val session: PlaybackSession? = null) {
        override fun toString() = "RelayEntry(playlist=$playlist, credentials=[redacted])"
    }
    private val entries = LinkedHashMap<String, Entry>(32, .75f, true)
    private val identities = mutableMapOf<String, String>()
    @Synchronized fun register(url: String, headers: SourceHeaders, playlist: Boolean = isHls(url), session: PlaybackSession? = null): String {
        Web.url(url)
        val identity = "$url\n${headers.referer}\n${headers.origin}\n${headers.extra.toSortedMap()}\n${headers.authOrigin}\n${hash(headers.authToken)}\n${session?.id}\n$playlist"
        val now = clock()
        val oldToken = identities[identity]
        val entry = entries[oldToken]
        if (entry != null && entry.expires > now) { entry.expires = now + limits.relayIdleMillis; return local(oldToken!!, playlist) }
        trim()
        while (entries.size >= limits.relayTokens) remove(entries.keys.first())
        val token = opaqueToken()
        entries[token] = Entry(url, headers, identity, now + limits.relayIdleMillis, playlist, session)
        identities[identity] = token
        return local(token, playlist)
    }
    @Synchronized fun get(token: String): Entry? {
        val entry = entries[token] ?: return null
        if (entry.expires <= clock()) { remove(token); return null }
        entry.expires = clock() + limits.relayIdleMillis
        return entry
    }
    @Synchronized fun trim() { entries.filterValues { it.expires <= clock() }.keys.toList().forEach(::remove) }
    @Synchronized fun size() = entries.size
    @Synchronized fun clear() { entries.clear(); identities.clear() }
    private fun remove(token: String) { entries.remove(token)?.let { if (identities[it.identity] == token) identities.remove(it.identity) } }
    private fun local(token: String, playlist: Boolean) = "$base/hls/$token/media${if (playlist) ".m3u8" else ".bin"}"
}
