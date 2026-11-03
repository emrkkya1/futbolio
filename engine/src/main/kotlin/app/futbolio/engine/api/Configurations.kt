package app.futbolio.engine.api

import app.futbolio.application.api.FutbolioConfig
import app.futbolio.application.configuration.ConfigurationValidator
import app.futbolio.application.configuration.ConfigurationCoordinator
import app.futbolio.application.api.ConfigurationController
import app.futbolio.application.ports.ConfigurationStore
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.configuration.ConfigurationCodec

/** Bundled configuration composition and schema-compatible import/export for hosts. */
object Configurations {
    private val providers = ProviderRegistry()
    private val validator = ConfigurationValidator(providers)
    private val codec = ConfigurationCodec(providers)
    val descriptors get() = providers.descriptors
    fun sources() = descriptors.map { it.source }
    fun defaults() = FutbolioConfig(sources = sources())
    fun coordinator(store: ConfigurationStore): ConfigurationController = ConfigurationCoordinator(store, defaults()) { validator.validate(it) }
    fun validate(config: FutbolioConfig) = validator.validate(config)
    fun validateSources(sources: List<SourceConfig>) = validator.validateSources(sources)
    fun validateSource(source: SourceConfig) = validator.validateSource(source)
    fun import(text: String) = codec.import(text)
    fun export(config: FutbolioConfig) = codec.export(config)
}

fun FutbolioConfig.Companion.import(text: String) = Configurations.import(text)
fun FutbolioConfig.export() = Configurations.export(this)
fun FutbolioConfig.validate() = Configurations.validate(this)
fun SourceConfig.validate() = Configurations.validateSource(this)
