package app.futbolio.desktop.presentation

import app.futbolio.application.api.*
import app.futbolio.desktop.DesktopSettings
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig

data class DesktopUiState(
    val configuration: ConfigurationState,
    val settings: DesktopSettings,
    val runtime: RuntimeSnapshot = RuntimeSnapshot(),
    val error: String = "",
    val descriptors: List<ProviderDescriptor> = emptyList(),
    val addresses: List<String> = emptyList(),
    val diagnosticsText: String = "Start Futbolio to view diagnostics.",
) {
    val config get() = configuration.config
    val manifest get() = settings.host().manifest
}

interface DesktopActions {
    fun onStart()
    fun onStop()
    fun onRefresh()
    fun onRefreshNetwork()
    fun onInstall()
    fun onCopyManifest()
    fun onInstallPage()
    fun onWorkbench()
    fun onImport()
    fun onExport()
    fun onCopyDiagnostics()
    fun onTest(sourceId: String, listingId: String? = null)
    fun onTestAll()
    fun onConfirm(sourceId: String, choiceId: String, works: Boolean)
    fun onCopyReport(sourceId: String)
    suspend fun saveSource(source: SourceConfig): SaveResult
    suspend fun removeSource(id: String): SaveResult
    suspend fun savePresentation(options: PresentationConfig): SaveResult
    suspend fun restoreDefaults(): SaveResult
    suspend fun saveNetwork(address: String, port: String, regenerate: Boolean): SaveResult
}
