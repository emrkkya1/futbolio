package app.futbolio.engine.adapters.hls

import app.futbolio.application.ports.StreamAssessment

/** Bounded parsing of a response already fetched for link extraction. No I/O or samples. */
internal object HlsDeclaredMetadata {
    fun read(text: String): StreamAssessment {
        val lines = text.take(64 * 1024).removePrefix("\uFEFF").lineSequence().map(String::trim).toList()
        if (lines.firstOrNull { it.isNotBlank() } != "#EXTM3U") return StreamAssessment()
        val variants = mutableListOf<String>()
        var pending: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) pending = line
            else if (line.isNotBlank() && !line.startsWith('#')) {
                pending?.let(variants::add)
                pending = null
            }
        }
        val tag = variants.maxByOrNull { attribute(it, "BANDWIDTH").toLongOrNull() ?: 0 } ?: return StreamAssessment()
        return StreamAssessment(
            resolution = attribute(tag, "RESOLUTION").takeIf { it.matches(Regex("\\d{1,5}x\\d{1,5}")) }.orEmpty(),
            frameRate = attribute(tag, "FRAME-RATE").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 240 },
            bitrate = attribute(tag, "BANDWIDTH").toLongOrNull()?.takeIf { it > 0 },
            codecs = attribute(tag, "CODECS").takeIf { it.length <= 160 && it.matches(Regex("[A-Za-z0-9., _-]+")) }.orEmpty(),
            adaptive = variants.size > 1,
        )
    }
    private fun attribute(line: String, key: String): String =
        Regex("(?:[:,])${Regex.escape(key)}=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"').orEmpty()
}
