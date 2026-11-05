package app.futbolio.android.bootstrap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import app.futbolio.android.AppSettings
import app.futbolio.android.RelayService
import app.futbolio.android.host.AndroidRuntimeHost
import app.futbolio.android.presentation.AndroidSession
import app.futbolio.application.api.*
import app.futbolio.application.ports.ConfigurationStore
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.*
import app.futbolio.engine.bootstrap.EngineFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FutbolioApplication : Application() {
    val container by lazy { AndroidContainer(this) }
}

class AndroidContainer(context: Context) : AndroidSession {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = AppSettings(context)
    private val coordinator = Configurations.coordinator(object : ConfigurationStore {
        override suspend fun read() = settings.config()
        override suspend fun write(config: FutbolioConfig) = settings.save(config)
    })
    override val configuration = coordinator.state
    private val mutableMessage = MutableStateFlow("")
    override val message = mutableMessage.asStateFlow()
    override val descriptors = Configurations.descriptors
    override val manifest = AppSettings.MANIFEST
    @Volatile private var loadFailed = false
    private val loaded = scope.async {
        try { coordinator.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true; notify("Saved settings could not be read. Defaults are shown; stored data is unchanged until you save.") }
    }
    val host = AndroidRuntimeHost(scope, coordinator, settings::desired, { settings.desired(it) }, { loaded.await(); check(!loadFailed) }) { config, poster, activity ->
        EngineFactory.create(HostConfig(), config, poster, activity)
    }
    override val runtime = host.status
    override fun notify(message: String) { mutableMessage.value = message }
    override suspend fun reload() {
        loaded.await()
        if (runtime.value.phase == RuntimePhase.STOPPED) try { coordinator.load(); loadFailed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true; notify("Saved settings could not be read. Stored data is unchanged.") }
    }
    override suspend fun start(): Boolean {
        loaded.await()
        if (loadFailed) { notify("Review and save valid provider settings before starting."); return false }
        return try {
            settings.desired(true)
            withContext(Dispatchers.Main) { context.startForegroundService(Intent(context, RelayService::class.java)) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { settings.desired(false); notify("Android did not allow startup. Keep Futbolio visible and retry."); false }
    }
    override suspend fun stop() {
        settings.desired(false)
        host.markStopping()
        withContext(Dispatchers.Main) { context.stopService(Intent(context, RelayService::class.java)) }
    }
    fun stopFromService() { scope.launch { stop() } }
    private suspend fun save(block: suspend () -> Unit): SaveResult = scope.async {
        loaded.await()
        try { block(); loadFailed = false; notify("Provider settings saved."); SaveResult(true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { SaveResult(false, if (failure is IllegalArgumentException) failure.message.orEmpty().take(150) else "Could not save provider settings.") }
    }.await()
    override suspend fun saveSource(source: SourceConfig) = save {
        val normalized = if (source.id.isBlank()) source.copy(id = "${source.kind}-${HostTokens.create().take(12)}") else source
        coordinator.update { it.copy(sources = it.sources.filterNot { old -> old.id == normalized.id } + normalized) }
    }
    override suspend fun removeSource(id: String) = save { coordinator.update { it.copy(sources = it.sources.filterNot { source -> source.id == id }) } }
    override suspend fun savePresentation(options: PresentationConfig) = save { coordinator.update {
        it.copy(presentation = it.presentation.copy(displayTimeZone = options.displayTimeZone, scoresEnabled = options.scoresEnabled,
            hideFinished = options.hideFinished, showHalftime = options.showHalftime, showTimesAndScores = options.showTimesAndScores))
    } }
    override suspend fun restoreDefaults() = save { coordinator.update { Configurations.defaults() } }
    override suspend fun importText(text: String) = save { val imported = Configurations.import(text); coordinator.update { imported } }
    override fun exportText() = configuration.value.config.export()
    override fun refresh() = host.refresh()
    override fun test(id: String, listing: String?) = host.test(id, listing)
    override fun testAll() = host.testAll()
    override fun confirm(id: String, choice: String, works: Boolean) = host.confirm(id, choice, works)
    override fun reportText(id: String) = runtime.value.reports[id]?.export()
    override fun diagnosticsText() = buildString {
        append("Futbolio ${Product.VERSION}\nAndroid API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}\nProcess PSS: ${android.os.Debug.getPss()} KiB\nScope: same-device-only\n")
        append(DiagnosticFormats.export(runtime.value.diagnostics))
        append("\nProviders:\n")
        runtime.value.providers.forEach { append("${it.name}: ${it.count} listings · ${it.error} ${it.warning}\n") }
    }
}
