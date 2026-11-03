package app.futbolio.engine.adapters.posters

import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.serialization.json.*

/** Classpath-only artwork. No HTTP fallback, filesystem probing, or fuzzy club matching. */
internal object BundledArtwork {
    private val options = PresentationConfig()
    private val indexes by lazy {
        listOf("index.json", "index-tff.json", "index-channels.json").mapNotNull { name ->
            resource(name)?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")?.let { Json.parseToJsonElement(it).jsonObject }
        }
    }
    private val teams by lazy {
        val result = mutableMapOf<String, String>()
        indexes.forEach { index ->
            val names = mutableMapOf<String, MutableSet<String>>()
            (index["teams"] as? JsonArray).orEmpty().forEach { item ->
                val row = item.jsonObject
                val file = row["file"]!!.jsonPrimitive.content
                (row["aliases"] as? JsonArray).orEmpty().forEach { alias ->
                    val key = MatchNormalizer.teamKey(alias.jsonPrimitive.content, options)
                    names.getOrPut(key) { linkedSetOf() } += file
                }
            }
            // Reviewed federation aliases override the generic pack, without making duplicate
            // sources for the same club count as ambiguous or allowing fuzzy club guesses.
            result.putAll(names.mapNotNull { (name, files) -> files.singleOrNull()?.let { name to it } }.toMap())
        }
        result.toMap()
    }
    private val competitions by lazy {
        indexes.flatMap { (it["competitions"] as? JsonArray).orEmpty() }.associate {
            val row = it.jsonObject
            row["id"]!!.jsonPrimitive.content to row["file"]!!.jsonPrimitive.content
        }
    }
    private val lightBackgrounds by lazy {
        indexes.flatMap { (it["competitions"] as? JsonArray).orEmpty() }.map { it.jsonObject }
            .filter { (it["lightBackground"] as? JsonPrimitive)?.booleanOrNull == true }
            .map { it["id"]!!.jsonPrimitive.content }.toSet()
    }
    private val cache = LinkedHashMap<String, ByteArray>(96, .75f, true)
    val background: ByteArray? by lazy { resource("stadium.jpg") }
    fun team(name: String): ByteArray? = teams[MatchNormalizer.teamKey(name, options)]?.let(::cached)
    fun competition(id: String): ByteArray? = competitions[id]?.let(::cached)
    fun competitionLightBackground(id: String) = id in lightBackgrounds
    private fun channelKey(name: String): String = java.text.Normalizer.normalize(name.lowercase(java.util.Locale.ROOT).replace('ı', 'i'), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(?:hd|fhd|uhd|4k|canli|izle|live)\\b"), "")
        .replace(Regex("[^a-z0-9]"), "")
    private val channels by lazy {
        indexes.flatMap { (it["channels"] as? JsonArray).orEmpty() }.flatMap { item ->
            val row = item.jsonObject
            row["aliases"]!!.jsonArray.map { channelKey(it.jsonPrimitive.content) to row["file"]!!.jsonPrimitive.content }
        }.toMap()
    }
    fun channel(name: String): ByteArray? = channels[channelKey(name)]?.let(::cached)

    @Synchronized private fun cached(file: String): ByteArray? {
        cache[file]?.let { return it }
        val bytes = resource(file) ?: return null
        cache[file] = bytes
        while (cache.size > 96) cache.remove(cache.keys.first())
        return bytes
    }

    private fun resource(file: String): ByteArray? {
        require(file.matches(Regex("[a-zA-Z0-9./_-]+")) && ".." !in file)
        return javaClass.getResourceAsStream("/artwork/$file")?.use { input ->
            input.readBytes().takeIf { it.size <= 1_048_576 }
        }
    }
}
