package app.futbolio.engine.adapters.configuration

import app.futbolio.application.api.FutbolioConfig
import app.futbolio.application.configuration.ConfigurationMigrations
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.ports.ProviderDirectory
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Explicit persistence DTOs protect the schema from domain refactoring. */
internal class ConfigurationCodec(private val providers: ProviderDirectory) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val validator = ConfigurationValidator(providers)

    fun import(text: String): FutbolioConfig {
        require(text.length <= 128 * 1024) { "Configuration is too large" }
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("Expected a configuration object")
        require(root["sources"] is JsonArray) { "Configuration must contain sources" }
        root["schema"]?.let { require(it.jsonPrimitive.content == "1") { "Unsupported configuration schema" } }
        val decoded = validator.validate(json.decodeFromString<ConfigDto>(text).model())
        val revision = (root["defaultsRevision"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        return validator.validate(ConfigurationMigrations.upgrade(decoded, "defaultsRevision" in root, revision, providers.descriptors.map { it.source }))
    }

    fun export(config: FutbolioConfig): String = json.encodeToString(ConfigDto.from(validator.validate(config)))
}

@Serializable
private data class SourceDto(
    val id: String, val kind: String, val name: String, val url: String,
    val apiUrl: String = "", val dataUrl: String = "", val enabled: Boolean = true,
    val fallbackUrls: List<String> = emptyList(), val numberedDomainWindow: Int = 0,
    val timeZone: String = "Europe/Istanbul",
) {
    fun model() = SourceConfig(id, kind, name, url, apiUrl, dataUrl, enabled, fallbackUrls, numberedDomainWindow, timeZone)
    companion object {
        fun from(source: SourceConfig) = SourceDto(source.id, source.kind, source.name, source.url,
            source.apiUrl, source.dataUrl, source.enabled, source.fallbackUrls, source.numberedDomainWindow, source.timeZone)
    }
}

@Serializable
private data class PresentationDto(
    val displayTimeZone: String = "Europe/Istanbul", val hideFinished: Boolean = true,
    val showHalftime: Boolean = true, val scoresEnabled: Boolean = false,
    val teamAliases: Map<String, String> = emptyMap(), val leagueAliases: Map<String, String> = emptyMap(),
) {
    fun model() = PresentationConfig(displayTimeZone, hideFinished, showHalftime, scoresEnabled, teamAliases, leagueAliases)
    companion object {
        fun from(value: PresentationConfig) = PresentationDto(value.displayTimeZone, value.hideFinished,
            value.showHalftime, value.scoresEnabled, value.teamAliases, value.leagueAliases)
    }
}

@Serializable
private data class ConfigDto(
    val schema: Int = 1,
    val sources: List<SourceDto>,
    val presentation: PresentationDto = PresentationDto(),
    val defaultsRevision: Int = 4,
) {
    fun model() = FutbolioConfig(schema, sources.map { it.model() }, presentation.model(), defaultsRevision)
    companion object {
        fun from(config: FutbolioConfig) = ConfigDto(config.schema, config.sources.map(SourceDto::from), PresentationDto.from(config.presentation), config.defaultsRevision)
    }
}
