package app.futbolio.engine.adapters.providers.shared

import app.futbolio.engine.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Static data extraction belongs to provider adapters, never resolution scheduling. */
internal object StaticStreamExtractor {
    fun extract(html: String, page: String, initial: List<String> = emptyList()): List<String> {
        val out = linkedSetOf<String>()
        fun add(value: String) {
            runCatching { Web.resolve(page, Text.decode(value)) }.getOrNull()?.takeIf {
                Regex("\\.(m3u8|mpd)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }?.let { if (out.size < 12) out += it }
        }
        initial.forEach(::add)
        for (pattern in listOf("\\b(?:source|file|src|hls|hlsUrl|streamUrl|stream_url)['\"]?\\s*[:=]\\s*['\"]([^'\"]+)['\"]", "<(?:video|source)\\b[^>]*\\bsrc=['\"]([^'\"]+)['\"]")) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).take(24).forEach { add(it.groupValues[1]) }
        }
        Regex("(?:source|file|streamUrl)\\s*[:=]\\s*atob\\(['\"]([A-Za-z0-9+/=]+)['\"]\\)", RegexOption.IGNORE_CASE).findAll(html).take(12).forEach {
            runCatching { add(Base64.getDecoder().decode(it.groupValues[1]).toString(Charsets.UTF_8)) }
        }
        if (out.isEmpty()) Regex("[\"']([^\"']*\\.(?:m3u8|mpd)(?:[?#][^\"']*)?)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).take(24).forEach { add(it.groupValues[1]) }
        return out.toList()
    }
}
