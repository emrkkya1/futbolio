package app.futbolio.engine.adapters.providers.taraftarium

import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.StaticStreamExtractor

internal object TaraftariumPlayerExtractor {
    fun extract(html: String, page: String): List<String> {
        val id = Web.query(page, "id")
        val base = Text.match(html, "\\bbaseUrl\\s*:\\s*['\"]([^'\"]+)['\"]")
        val mapped = if (base.isNotEmpty() && id.isNotEmpty() && Regex("CONFIG\\.baseUrl\\s*\\+\\s*\\w+\\s*\\+\\s*['\"]/mono\\.m3u8['\"]").containsMatchIn(html))
            listOf(if (id.startsWith("http://") || id.startsWith("https://")) id else "$base${Web.enc(id)}/mono.m3u8") else emptyList()
        return StaticStreamExtractor.extract(html, page, mapped)
    }
}
