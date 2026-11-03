package app.futbolio.domain.text

import java.text.Normalizer
import java.util.Locale

/** Plain-text football classification, independent of HTML and provider formats. */
object TextPolicy {
    private val marks = Regex("\\p{M}")
    private val turkish = Regex("super lig|superlig|turk|trendyol|tff|1\\.? lig|galatasaray|fenerbahce|besiktas|trabzonspor|basaksehir|samsunspor|goztepe|eyupspor|konyaspor|kasimpasa|gaziantep|kayserispor|rizespor|genclerbirligi|kocaelispor|alanyaspor|antalyaspor|karagumruk")
    private val otherSport = Regex("basket|voley|volley|tennis|tenis|\\batp\\b|\\bwta\\b|\\bnba\\b|\\bnfl\\b|\\bnhl\\b|hockey|ufc|mma|formula|grand prix|hentbol|handball|motogp")
    fun normalize(value: String): String = Normalizer.normalize(value.replace('ı', 'i'), Normalizer.Form.NFD)
        .replace(marks, "")
        .lowercase(Locale.ROOT)

    fun isTurkish(value: String): Boolean = turkish
        .containsMatchIn(normalize(value))

    fun isOtherSport(value: String): Boolean = otherSport
        .containsMatchIn(normalize(value))
}
