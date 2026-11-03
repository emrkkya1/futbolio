package app.futbolio.application.api

import kotlinx.coroutines.flow.StateFlow

data class ConfigurationState(
    val config: FutbolioConfig,
    val desiredRevision: Long = 0,
    val appliedRevision: Long? = null,
    val applyError: String = "",
)
fun interface ConfigurationTarget { fun apply(config: FutbolioConfig) }
interface ConfigurationController {
    val state: StateFlow<ConfigurationState>
    suspend fun load()
    suspend fun attach(target: ConfigurationTarget?)
    suspend fun update(transform: (FutbolioConfig) -> FutbolioConfig): ConfigurationState
}
