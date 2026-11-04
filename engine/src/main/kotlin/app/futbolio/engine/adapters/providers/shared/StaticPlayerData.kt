package app.futbolio.engine.adapters.providers.shared

import app.futbolio.engine.LiteralReader
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.util.Base64

/** Extract data, not code: variable names, assignment styles and script formatting are irrelevant.
 * All scanning, decoded payloads, traversal and candidate sizes are bounded. No script is executed.
 */
internal object StaticPlayerData {
    private const val MAX_DOCUMENT = 2 * 1024 * 1024
    private const val MAX_LITERAL = 64 * 1024
    private const val MAX_CANDIDATES = 128

    fun objects(html: String): List<JsonObject> {
        require(html.length <= MAX_DOCUMENT)
        val out = mutableListOf<JsonObject>()
        var attempts = 0
        fun collect(value: JsonElement, depth: Int = 0) {
            if (depth > 20 || out.size >= MAX_CANDIDATES) return
            when (value) {
                is JsonObject -> { out += value; value.values.forEach { collect(it, depth + 1) } }
                is JsonArray -> value.take(MAX_CANDIDATES).forEach { collect(it, depth + 1) }
                else -> Unit
            }
        }
        fun encoded(value: String) {
            if (value.length > MAX_LITERAL) return
            val text = if (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")) value else
                runCatching { Base64.getDecoder().decode(value).toString(Charsets.UTF_8) }.getOrNull() ?: return
            if (text.length > MAX_LITERAL || ++attempts > MAX_CANDIDATES) return
            runCatching { LiteralReader(text).read() }.getOrNull()?.let { collect(it) }
        }
        fun scan(text: String) {
            var index = 0
            val initialAttempts = attempts
            while (index < text.length && attempts < MAX_CANDIDATES && attempts - initialAttempts < 16 && out.size < MAX_CANDIDATES) {
                when {
                    text.startsWith("//", index) -> index = text.indexOf('\n', index + 2).takeIf { it >= 0 } ?: text.length
                    text.startsWith("/*", index) -> index = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
                    text.startsWith("JSON.parse", index) -> {
                        val opening = index + "JSON.parse".length
                        val start = text.indexOf('(', opening).takeIf { it >= opening && text.substring(opening, it).isBlank() }
                        if (start != null) {
                            val argument = text.substring(start + 1, minOf(text.length, start + 1 + MAX_LITERAL)).trimStart()
                            val base64 = Regex("^atob\\s*\\(").find(argument)
                            val literal = if (base64 != null) argument.substring(base64.range.last + 1) else argument
                            val reader = LiteralReader(literal)
                            val value = runCatching { reader.read() as? JsonPrimitive }.getOrNull()
                            val tail = literal.drop(reader.position).trimStart()
                            if (value != null && (if (base64 == null) tail.startsWith(")") else Regex("^\\)\\s*\\)").containsMatchIn(tail))) encoded(value.content)
                        }
                        index += "JSON.parse".length
                    }
                    text[index] == '\'' || text[index] == '"' || text[index] == '`' -> {
                        val quote = text[index++]
                        while (index < text.length) {
                            val char = text[index++]
                            if (char == '\\') index = minOf(index + 1, text.length) else if (char == quote) break
                        }
                    }
                    text[index] == '{' || text[index] == '[' -> {
                        attempts++
                        val reader = LiteralReader(text.substring(index, minOf(text.length, index + MAX_LITERAL)))
                        val value = runCatching { reader.read() }.getOrNull()
                        if (value != null) { collect(value); index += reader.position } else index++
                    }
                    else -> index++
                }
            }
        }
        val document = Jsoup.parse(html)
        // Large inline ad/player libraries must not exhaust the candidate budget before
        // a small bootstrap later in the document. Give each script a bounded fair turn.
        document.select("[data-config], [data-player-config]").take(8).forEach { node ->
            for (key in listOf("data-config", "data-player-config")) if (node.hasAttr(key)) encoded(node.attr(key))
        }
        val scripts = document.select("script").filter { it.data().isNotBlank() }.sortedBy { it.data().length }.take(96)
        if (scripts.isEmpty()) scan(html) else scripts.forEach { scan(it.data()) }
        return out.distinct()
    }
}
