package app.futbolio.engine.adapters.providers.shared

import app.futbolio.engine.*

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.application.resolution.deduplicateStreams

import kotlinx.coroutines.*

/** Reusable literal/iframe/player-protocol helpers, not provider-name dispatch. */
internal class StaticPlayerResolver(
    private val net: Upstream,
    private val extract: (String, String) -> List<String> = { html, page -> StaticStreamExtractor.extract(html, page) },
    private val specialPlayer: suspend (Player, Listing) -> List<StreamChoice>? = { _, _ -> null },
) {
    suspend fun resolve(item: Listing): List<StreamChoice> = coroutineScope {
        deduplicateStreams(item.players.ifEmpty { listOf(Player(item.page)) }.take(4).map { player -> async {
            try {
                resolvePlayer(player, item).map {
                    it.copy(title = listOf(item.title, player.label, player.language).filter(String::isNotBlank).joinToString("\n"))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        } }.awaitAll().flatten()).take(12)
    }
    private suspend fun resolvePlayer(player: Player, item: Listing): List<StreamChoice> {
        if (player.target == "m3u8") return listOf(StreamChoice("", player.url, SourceHeaders(item.page, Web.origin(item.page)), mediaType = "hls"))
        return specialPlayer(player, item) ?: page(player.url, item.page, 0, mutableSetOf())
    }
    private suspend fun page(url: String, parent: String, depth: Int, seen: MutableSet<String>): List<StreamChoice> {
        if (depth > 2 || !seen.add(url)) return emptyList()
        val doc = net.text(url, SourceHeaders(parent))
        if (doc.text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) return listOf(StreamChoice("", doc.url, SourceHeaders(parent, Web.origin(parent)), mediaType = "hls"))
        val referer = if (Regex("<meta[^>]*name=['\"]referrer['\"][^>]*content=['\"]origin['\"]", RegexOption.IGNORE_CASE).containsMatchIn(doc.text)) Web.origin(doc.url) + "/" else doc.url
        val streams = extract(doc.text, doc.url).map { StreamChoice("", it, SourceHeaders(referer, Web.origin(doc.url))) }
        if (streams.isNotEmpty()) return streams
        return Text.frames(doc.text, doc.url).flatMap {
            try { page(it, doc.url, depth + 1, seen) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }
}
