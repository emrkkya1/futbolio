package app.futbolio.desktop

import app.futbolio.engine.api.*
import app.futbolio.engine.bootstrap.EngineFactory
import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.application.ports.ConfigurationStore
import app.futbolio.desktop.presentation.*
import app.futbolio.desktop.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class DesktopSettings(val schema: Int = 1, val port: Int = 7000, val lanAddress: String = "", val token: String = HostTokens.create()) {
    private fun accessToken() = token.takeIf { lanAddress.isNotBlank() }.orEmpty()
    fun validate(addresses: List<String> = lanAddresses()): DesktopSettings {
        require(schema == 1)
        require(lanAddress.isBlank() || lanAddress in addresses) { "Select an address currently assigned to this computer" }
        HostConfig(port, if (lanAddress.isBlank()) "127.0.0.1" else lanAddress, lanAddress.ifBlank { "127.0.0.1" }, accessToken(), desktop = true)
        return this
    }
    fun host() = HostConfig(port, lanAddress.ifBlank { "127.0.0.1" }, lanAddress.ifBlank { "127.0.0.1" }, accessToken(), desktop = true)
    override fun toString() = "DesktopSettings(schema=$schema, port=$port, token=[redacted])"
}

fun lanAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress }.distinct().sorted()

fun atomicWrite(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, ".futbolio-", ".tmp")
    try {
        Files.writeString(temp, text)
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

private val desktopJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

class DesktopHost(
    val directory: Path = Path.of(System.getProperty("user.home"), ".futbolio"),
    private val platform: DesktopPlatform = SystemDesktopPlatform,
    private val createEngine: (HostConfig, FutbolioConfig, PosterRenderer) -> EngineControl = { host, config, poster ->
        EngineFactory.create(host, config, poster)
    },
) : AutoCloseable, DesktopActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private var engine: EngineControl? = null
    private var observing: Job? = null
    private val disposed = java.util.concurrent.atomic.AtomicBoolean()
    private val mutableRuntime = MutableStateFlow(RuntimeSnapshot())
    val runtime = mutableRuntime.asStateFlow()
    private val coordinator = Configurations.coordinator(object : ConfigurationStore {
        override suspend fun read() = directory.resolve("providers.json").let { if (Files.exists(it)) Configurations.import(Files.readString(it)) else null }
        override suspend fun write(config: FutbolioConfig) = atomicWrite(directory.resolve("providers.json"), config.export())
    })
    val configuration = coordinator.state
    private val _settings = MutableStateFlow(DesktopSettings())
    val settings = _settings.asStateFlow()
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    private val addresses = MutableStateFlow(platform.addresses())
    val ui = combine(configuration, settings, runtime, error, addresses) { config, settings, runtime, error, addresses ->
        DesktopUiState(config, settings, runtime, error.ifBlank { config.applyError }, Configurations.descriptors, addresses,
            if (runtime.running) DiagnosticFormats.export(runtime.diagnostics) else "Start Futbolio to view diagnostics.")
    }.stateIn(scope, SharingStarted.Eagerly, DesktopUiState(configuration.value, settings.value, descriptors = Configurations.descriptors, addresses = addresses.value))
    init {
        try {
            runBlocking { coordinator.load() }
            val settingsFile = directory.resolve("desktop.json")
            if (Files.exists(settingsFile)) {
                val loaded = desktopJson.decodeFromString<DesktopSettings>(Files.readString(settingsFile))
                require(loaded.schema == 1); loaded.host() // Validate shape, but tolerate a disconnected network until Start.
                _settings.value = loaded
            } else atomicWrite(settingsFile, desktopJson.encodeToString(_settings.value))
        } catch (_: Exception) { _error.value = "Could not read saved settings. Existing files are untouched; review settings before saving." }
    }
    suspend fun start() = lifecycle.withLock {
        check(!disposed.get()) { "Host is disposed" }
        if (engine != null) return@withLock
        mutableRuntime.value = RuntimeSnapshot(RuntimePhase.STARTING); _error.value = ""
        var created: EngineControl? = null
        try {
            val settings = _settings.value.validate(platform.addresses())
            val running = createEngine(settings.host(), configuration.value.config, DesktopPosters())
            created = running
            running.start()
            coordinator.attach(ConfigurationTarget { running.configure(it.sources, it.presentation) })
            engine = running
            mutableRuntime.value = RuntimeSnapshot(RuntimePhase.RUNNING, running.providerStatuses.value, running.providerReports.value, running.probeCandidates.value, running.diagnosticSnapshot())
            observing = scope.launch { running.snapshot.collect { mutableRuntime.value = it.copy(phase = running.phase.value) } }
        } catch (e: CancellationException) { withContext(NonCancellable) { created?.shutdown() }; mutableRuntime.value = RuntimeSnapshot(); throw e }
        catch (_: Exception) { created?.shutdown(); mutableRuntime.value = RuntimeSnapshot(); _error.value = "Could not start. Check the selected network address and whether the port is occupied." }
    }
    suspend fun stop() = lifecycle.withLock {
        observing?.cancel(); observing = null
        coordinator.attach(null)
        val previous = engine
        engine = null
        mutableRuntime.value = RuntimeSnapshot(RuntimePhase.STOPPING)
        try { previous?.shutdown() } finally { mutableRuntime.value = RuntimeSnapshot() }
    }
    suspend fun saveConfig(config: FutbolioConfig) { coordinator.update { config } }
    suspend fun saveSettings(settings: DesktopSettings) = lifecycle.withLock {
        require(engine == null) { "Stop Futbolio before changing network settings" }
        settings.validate(platform.addresses()); atomicWrite(directory.resolve("desktop.json"), desktopJson.encodeToString(settings)); _settings.value = settings
    }
    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { _error.value = if (e is IllegalArgumentException) e.message.orEmpty().take(150) else "Operation failed. Check file permissions and settings." } } }
    private suspend fun save(block: suspend () -> Unit): SaveResult = scope.async {
        try { block(); _error.value = ""; SaveResult(true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { SaveResult(false, if (failure is IllegalArgumentException) failure.message.orEmpty().take(150) else "Could not save settings. Check file permissions and retry.") }
    }.await()
    override fun onStart() = launch { start() }
    override fun onStop() = launch { stop() }
    override fun onRefresh() { engine?.refreshProviders() }
    override fun onRefreshNetwork() = launch { addresses.value = platform.addresses() }
    override fun onInstall() = launch { platform.browse(settings.value.host().manifest.replace("http://", "stremio://")) }
    override fun onCopyManifest() = launch { platform.copy(settings.value.host().manifest) }
    override fun onInstallPage() = launch { platform.browse(settings.value.host().base + "/") }
    override fun onWorkbench() = launch { platform.browse(settings.value.host().base + "/workbench") }
    override fun onImport() = launch { platform.chooseFile(false)?.let { saveConfig(Configurations.import(Files.readString(it))) } }
    override fun onExport() = launch { platform.chooseFile(true)?.let { atomicWrite(it, configuration.value.config.export()) } }
    override fun onCopyDiagnostics() = launch { platform.copy(ui.value.diagnosticsText) }
    override fun onTest(sourceId: String, listingId: String?) { engine?.testProvider(sourceId, listingId) }
    override fun onTestAll() { engine?.testAllProviders() }
    override fun onConfirm(sourceId: String, choiceId: String, works: Boolean) { engine?.confirmPlayback(sourceId, choiceId, works) }
    override fun onCopyReport(sourceId: String) = launch { runtime.value.reports[sourceId]?.let { platform.copy(it.export()) } }
    override suspend fun saveSource(source: SourceConfig) = save {
        val normalized = if (source.id.isBlank()) source.copy(id = "${source.kind}-${HostTokens.create().take(12)}") else source
        coordinator.update { it.copy(sources = it.sources.filterNot { old -> old.id == normalized.id } + normalized) }
    }
    override suspend fun removeSource(id: String) = save { coordinator.update { it.copy(sources = it.sources.filterNot { source -> source.id == id }) } }
    override suspend fun savePresentation(options: PresentationConfig) = save { coordinator.update {
        it.copy(presentation = it.presentation.copy(displayTimeZone = options.displayTimeZone, scoresEnabled = options.scoresEnabled,
            hideFinished = options.hideFinished, showHalftime = options.showHalftime))
    } }
    override suspend fun restoreDefaults() = save { coordinator.update { Configurations.defaults() } }
    override suspend fun saveNetwork(address: String, port: String, regenerate: Boolean) = save {
        saveSettings(settings.value.copy(lanAddress = address, port = port.toIntOrNull() ?: throw IllegalArgumentException("Use a valid port"),
            token = if (regenerate) HostTokens.create() else settings.value.token))
    }
    fun requestClose(onClosed: () -> Unit = {}) {
        if (!disposed.compareAndSet(false, true)) return
        scope.launch { try { stop() } finally { java.awt.EventQueue.invokeLater(onClosed); scope.cancel() } }
    }
    override fun close() { disposed.set(true); runBlocking { stop() }; scope.cancel() }
}
