package app.futbolio.domain.provider

import java.time.ZoneId

/** Immutable provider-instance settings. Adapter availability is validated by the application. */
data class SourceConfig(
    val id: String,
    val kind: String,
    val name: String,
    val url: String,
    val apiUrl: String = "",
    val dataUrl: String = "",
    val enabled: Boolean = true,
    val fallbackUrls: List<String> = emptyList(),
    val numberedDomainWindow: Int = 0,
    val timeZone: String = "Europe/Istanbul",
) {
    fun validateShape() {
        require(id.matches(Regex("[a-z0-9-]{1,40}"))) { "Source ID must be a lowercase slug" }
        require(kind.isNotBlank()) { "Missing provider kind" }
        require(name.isNotBlank() && name.length <= 80) { "Name must be 1–80 characters" }
        SourceUrl.parse(url)
        if (apiUrl.isNotBlank()) SourceUrl.parse(apiUrl)
        if (dataUrl.isNotBlank()) SourceUrl.parse(dataUrl)
        require(fallbackUrls.size <= 5) { "At most five fallback URLs" }
        fallbackUrls.forEach(SourceUrl::parse)
        require(numberedDomainWindow in 0..3) { "Domain window must be 0–3" }
        ZoneId.of(timeZone)
    }

    override fun toString() = "SourceConfig(id=$id, kind=$kind, URLs=[redacted])"
}
