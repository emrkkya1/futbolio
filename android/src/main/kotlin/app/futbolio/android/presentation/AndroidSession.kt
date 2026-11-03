package app.futbolio.android.presentation

import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.flow.StateFlow

interface AndroidSession {
    val configuration: StateFlow<ConfigurationState>
    val runtime: StateFlow<RuntimeSnapshot>
    val message: StateFlow<String>
    val descriptors: List<ProviderDescriptor>
    val manifest: String
    suspend fun start(): Boolean
    suspend fun stop()
    suspend fun reload()
    fun notify(message: String)
    fun refresh()
    fun test(id: String, listing: String?)
    fun testAll()
    fun confirm(id: String, choice: String, works: Boolean)
    suspend fun saveSource(source: SourceConfig): SaveResult
    suspend fun removeSource(id: String): SaveResult
    suspend fun savePresentation(options: PresentationConfig): SaveResult
    suspend fun restoreDefaults(): SaveResult
    suspend fun importText(text: String): SaveResult
    fun exportText(): String
    fun reportText(id: String): String?
    fun diagnosticsText(): String
}

data class AndroidUiState(
    val configuration: ConfigurationState,
    val runtime: RuntimeSnapshot,
    val message: String,
    val descriptors: List<ProviderDescriptor>,
    val manifest: String,
    val diagnostics: String,
) { val config get() = configuration.config }

interface TvActions {
    fun start()
    fun stop()
    fun install()
    fun copyManifest()
    fun importProviders()
    fun exportProviders()
    fun copyDiagnostics()
    fun refresh()
    fun test(id: String, listing: String? = null)
    fun testAll()
    fun confirm(id: String, choice: String, works: Boolean)
    fun copyReport(id: String)
    suspend fun saveSource(source: SourceConfig): SaveResult
    suspend fun removeSource(id: String): SaveResult
    suspend fun savePresentation(options: PresentationConfig): SaveResult
    suspend fun restoreDefaults(): SaveResult
}

sealed interface AndroidEffect {
    data class Copy(val text: String) : AndroidEffect
    data object Install : AndroidEffect
    data object Import : AndroidEffect
    data object Export : AndroidEffect
    data object Notifications : AndroidEffect
}
