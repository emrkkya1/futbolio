package app.futbolio.engine.adapters.relay

import app.futbolio.engine.Web

internal object Hls {
    fun rewrite(text: String, upstream: String, register: (String) -> String): String =
        text.removePrefix("\uFEFF").split(Regex("\\r?\\n")).joinToString("\n") { original ->
            val line = original.trim()
            when {
                line.isEmpty() -> original
                !line.startsWith('#') -> register(Web.resolve(upstream, line))
                else -> Regex("\\bURI=\"([^\"]+)\"").replace(line) { match ->
                    val uri = match.groupValues[1]
                    "URI=\"${if (uri.startsWith("data:")) uri else register(Web.resolve(upstream, uri))}\""
                }
            }
        }
}
