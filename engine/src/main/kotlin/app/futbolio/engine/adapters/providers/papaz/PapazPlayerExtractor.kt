package app.futbolio.engine.adapters.providers.papaz

import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.StaticStreamExtractor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

internal object PapazPlayerExtractor {
    fun extract(html: String, page: String): List<String> {
        val id = Web.query(page, "id")
        val bases = Regex("this\\.baseurls\\s*=\\s*").find(html)
        val mapped = if (bases != null && id.matches(Regex("(?:androstreamlive|facebooklive)[\\w-]+"))) runCatching {
            val urls = LiteralReader(html, bases.range.last + 1).read() as JsonArray
            val special = Text.match(html, "return\\s*['\"]([^'\"]+\\.m3u8)['\"]")
            val path = if (id in listOf("androstreamlivebs1", "facebooklivebs1") && Web.query(page, "real") != "1" && html.contains("!useRealStream") && special.isNotEmpty()) special else "$id.m3u8"
            urls.take(6).map { Web.resolve(it.jsonPrimitive.content, path) }
        }.getOrDefault(emptyList()) else emptyList()
        return StaticStreamExtractor.extract(html, page, mapped)
    }
}
