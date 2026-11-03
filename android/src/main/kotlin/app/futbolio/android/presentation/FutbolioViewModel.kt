package app.futbolio.android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FutbolioViewModel(private val session: AndroidSession) : ViewModel(), TvActions {
    private val mutableEffects = MutableSharedFlow<AndroidEffect>(extraBufferCapacity = 8)
    val effects = mutableEffects.asSharedFlow()
    val state = combine(session.configuration, session.runtime, session.message) { configuration, runtime, message ->
        AndroidUiState(configuration, runtime, message, session.descriptors, session.manifest, session.diagnosticsText())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AndroidUiState(session.configuration.value, session.runtime.value,
        session.message.value, session.descriptors, session.manifest, session.diagnosticsText()))

    init { viewModelScope.launch { session.reload() } }
    fun notify(message: String) = session.notify(message)
    fun exportText() = session.exportText()
    suspend fun importText(text: String) = session.importText(text)
    private fun effect(effect: AndroidEffect) { mutableEffects.tryEmit(effect) }
    override fun start() { viewModelScope.launch { if (session.start()) effect(AndroidEffect.Notifications) } }
    override fun stop() { viewModelScope.launch { session.stop() } }
    override fun install() { if (session.runtime.value.running) effect(AndroidEffect.Install) else session.notify("Start Futbolio first.") }
    override fun copyManifest() = effect(AndroidEffect.Copy(session.manifest))
    override fun importProviders() = effect(AndroidEffect.Import)
    override fun exportProviders() = effect(AndroidEffect.Export)
    override fun copyDiagnostics() = effect(AndroidEffect.Copy(session.diagnosticsText()))
    override fun refresh() = session.refresh()
    override fun test(id: String, listing: String?) = session.test(id, listing)
    override fun testAll() = session.testAll()
    override fun confirm(id: String, choice: String, works: Boolean) = session.confirm(id, choice, works)
    override fun copyReport(id: String) { session.reportText(id)?.let { effect(AndroidEffect.Copy(it)) } }
    override suspend fun saveSource(source: SourceConfig) = session.saveSource(source)
    override suspend fun removeSource(id: String) = session.removeSource(id)
    override suspend fun savePresentation(options: PresentationConfig) = session.savePresentation(options)
    override suspend fun restoreDefaults() = session.restoreDefaults()
}
