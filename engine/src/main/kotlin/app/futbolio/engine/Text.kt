package app.futbolio.engine

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

object Web {
    fun url(value: String): URI {
        return app.futbolio.domain.provider.SourceUrl.parse(value)
    }
    fun resolve(base: String, relative: String) = url(url(base).resolve(Text.decode(relative.trim())).toString()).toString()
    fun origin(value: String) = url(value).let { "${it.scheme}://${it.rawAuthority}" }
    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
    fun params(query: String?): Map<String, String> = query?.split('&')?.filter { it.isNotEmpty() }?.associate {
        val pair = it.split('=', limit = 2); dec(pair[0]) to dec(pair.getOrElse(1) { "" })
    } ?: emptyMap()
    fun query(page: String, name: String) = params(url(page).rawQuery)[name].orEmpty()
}

object Text {
    fun decode(s: String): String = Parser.unescapeEntities(s.replace("\\/", "/").replace("\\u002f", "/", true).replace("\\u0026", "&"), false)
    fun plain(s: String): String = Jsoup.parse(s).text().trim()
    fun norm(s: String) = app.futbolio.domain.text.TextPolicy.normalize(s)
    fun turkish(s: String) = app.futbolio.domain.text.TextPolicy.isTurkish(s)
    fun otherSport(s: String) = app.futbolio.domain.text.TextPolicy.isOtherSport(s)
    fun match(s: String, pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(s)?.groupValues?.get(1).orEmpty()
    fun anchors(html: String, base: String): List<Pair<String, String>> = Jsoup.parse(html, base).select("a[href]").take(2000).mapNotNull {
        runCatching { Web.resolve(base, it.attr("href")) to it.text() }.getOrNull()
    }
    fun scripts(html: String, base: String): List<String> = Jsoup.parse(html, base).select("script[src]").mapNotNull {
        runCatching { Web.resolve(base, it.attr("src")) }.getOrNull()
    }
    fun frames(html: String, base: String): List<String> = Jsoup.parse(html, base).select("iframe[src]").take(3).mapNotNull {
        runCatching { Web.resolve(base, it.attr("src")) }.getOrNull()
    }
    fun variable(script: String, name: String): List<JsonElement> {
        val m = Regex("(?:const|let|var)\\s+${Regex.escape(name)}\\s*=\\s*").find(script) ?: return emptyList()
        return runCatching { LiteralReader(script, m.range.last + 1).read() as? JsonArray }.getOrNull()?.toList().orEmpty()
    }
    fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.n(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
fun JsonObject.b(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true || s(key) == "1"
fun JsonObject.a(key: String) = (get(key) as? JsonArray)?.toList().orEmpty()

/** Restricted JS literal reader: arrays, objects and scalar data only. Never evals code. */
class LiteralReader(private val text: String, private var i: Int = 0) {
    internal val position: Int get() = i
    private var nodes = 0
    private fun ws() {
        while (i < text.length) when {
            text[i].isWhitespace() -> i++
            text.startsWith("//", i) -> i = text.indexOf('\n', i + 2).takeIf { it >= 0 } ?: text.length
            text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: bad()
            else -> return
        }
    }
    private fun bad(): Nothing = throw IllegalArgumentException("Unsupported data literal")
    fun read(depth: Int = 0): JsonElement {
        if (depth > 40 || ++nodes > 100_000) bad()
        ws(); if (i >= text.length) bad()
        return when (val c = text[i++]) {
            '\'', '"' -> JsonPrimitive(string(c))
            '[', '{' -> {
                val end = if (c == '[') ']' else '}'
                val list = mutableListOf<JsonElement>(); val map = linkedMapOf<String, JsonElement>()
                ws()
                while (i < text.length && text[i] != end) {
                    if (c == '[') list += read(depth + 1) else {
                        ws(); if (i >= text.length) bad()
                        val key = if (text[i] == '\'' || text[i] == '"') string(text[i++]) else {
                            val start = i
                            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_$")) i++
                            if (start == i) bad(); text.substring(start, i)
                        }
                        ws(); if (i >= text.length || text[i++] != ':') bad()
                        map[key] = read(depth + 1)
                    }
                    ws()
                    if (i < text.length && text[i] == ',') { i++; ws() } else if (i >= text.length || text[i] != end) bad()
                }
                if (i >= text.length) bad(); i++
                if (c == '[') JsonArray(list) else JsonObject(map)
            }
            else -> {
                i--; val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "+-.")) i++
                val token = text.substring(start, i)
                if (token !in listOf("true", "false", "null") && !token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) bad()
                codec.parseToJsonElement(token)
            }
        }
    }
    private fun string(quote: Char): String {
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i++]; if (c == quote) return out.toString()
            if (c != '\\') out.append(c) else {
                if (i >= text.length) bad()
                when (val e = text[i++]) {
                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    'u', 'x' -> {
                        val len = if (e == 'u') 4 else 2
                        if (i + len > text.length) bad()
                        out.append((text.substring(i, i + len).toIntOrNull(16) ?: bad()).toChar()); i += len
                    }
                    else -> out.append(e)
                }
            }
        }
        bad()
    }
}
