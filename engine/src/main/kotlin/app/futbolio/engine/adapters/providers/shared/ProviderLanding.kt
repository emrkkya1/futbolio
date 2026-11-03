package app.futbolio.engine.adapters.providers.shared

import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.Text
import app.futbolio.engine.Web
import app.futbolio.engine.LiteralReader
import app.futbolio.engine.obj
import app.futbolio.engine.a
import app.futbolio.engine.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Follows only the provider's labeled, published entry link. */
internal suspend fun discoverLanding(source: SourceConfig, net: Upstream): Document {
    var home = net.text(source.url)
    val visited = linkedSetOf(home.url)
    repeat(3) {
        val link = publishedEntry(home) ?: return home
        check(visited.add(link)) { "Provider entry link loop" }
        home = net.text(link, SourceHeaders(home.url))
        check(visited.add(home.url) || home.url == link) { "Provider entry redirect loop" }
    }
    check(publishedEntry(home) == null) { "Too many provider entry links" }
    return home
}

private val entryLabel = Regex("canli\\s*mac\\s*girisi|siteye\\s*giris")

/** Bitly renders its buttons in literal data rather than HTML anchors. Never run its JS. */
internal fun publishedEntry(home: Document): String? {
    val anchors = Text.anchors(home.text, home.url)
    anchors.firstOrNull {
        entryLabel.containsMatchIn(Text.norm(it.second)) && it.first != home.url
    }?.let { return it.first }
    if (Web.url(home.url).host in setOf("bit.ly", "www.bit.ly")) {
        anchors.firstOrNull { Text.norm(it.second).trim() == "continue to destination" && it.first != home.url }
            ?.let { return it.first }
    }
    val marker = Regex("\\b(?:window\\.)?launchpadData\\s*=\\s*").find(home.text) ?: return null
    val data = runCatching { LiteralReader(home.text, marker.range.last + 1).read() as? JsonObject }.getOrNull() ?: return null
    for (value in data.a("buttons").take(32)) {
        val button = value.obj()
        if ((button["is_active"] as? JsonPrimitive)?.booleanOrNull == false) continue
        val content = button["content"]?.obj() ?: JsonObject(emptyMap())
        if (!entryLabel.containsMatchIn(Text.norm(button.s("title").ifBlank { content.s("title") }))) continue
        val target = button.s("long_url").ifBlank { content.s("target").ifBlank { button.s("bitlink") } }
        if (target.isBlank()) continue
        val url = runCatching { Web.url(if (target.contains("://")) target else "https://$target").toString() }.getOrNull()
        if (url != null && url != home.url) return url
    }
    return null
}
