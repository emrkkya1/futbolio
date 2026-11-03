package app.futbolio.application.configuration

import app.futbolio.application.api.FutbolioConfig
import app.futbolio.application.api.ResourceLimits
import app.futbolio.application.ports.ProviderDirectory
import app.futbolio.domain.provider.SourceConfig

class ConfigurationValidator(private val providers: ProviderDirectory, private val limits: ResourceLimits = ResourceLimits()) {
    fun validateSource(source: SourceConfig) {
        source.validateShape()
        require(providers.descriptors.any { it.kind == source.kind }) { "Unsupported provider adapter" }
    }

    fun validateSources(sources: List<SourceConfig>) {
        require(sources.size <= limits.sources) { "At most ${limits.sources} sources are supported" }
        require(sources.map { it.id }.distinct().size == sources.size) { "Source IDs must be unique" }
        sources.forEach(::validateSource)
    }

    fun validate(config: FutbolioConfig): FutbolioConfig {
        require(config.schema == 1) { "Unsupported configuration schema" }
        validateSources(config.sources)
        config.presentation.validate()
        return config
    }
}
