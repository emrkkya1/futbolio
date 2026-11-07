package app.futbolio.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import app.futbolio.application.presentation.providerTestsBusy
import app.futbolio.application.api.*
import app.futbolio.desktop.presentation.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.launch

internal val theme = darkColorScheme(primary = Color(0xffb4ff62), secondary = Color(0xff1ee69b), background = Color(0xff040705),
    surface = Color(0xff101713), surfaceVariant = Color(0xff18241c), onPrimary = Color(0xff05160c), onSurface = Color(0xfff1f8f3), outline = Color(0xff355441))

/** Passive platform layout: immutable values and actions only, no engine, store or network access. */
@Composable internal fun DesktopScreen(state: DesktopUiState, actions: DesktopActions) {
    val config = state.config
    val running = state.runtime.running
    var tab by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<SourceConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<SourceConfig?>(null) }
    var preferences by remember { mutableStateOf(false) }
    var defaults by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var portNoticeDismissed by remember(state.settings.port) { mutableStateOf(false) }
    if (running && state.settings.port != 7000 && !portNoticeDismissed) {
        AlertDialog(onDismissRequest = { portNoticeDismissed = true }, title = { Text("Futbolio port changed") },
            text = { Text("Futbolio is using port ${state.settings.port}. If you already added the add-on in Stremio with port 7000 or another old port, remove it and add it again using the new URL: ${state.manifest}") },
            confirmButton = { Button(onClick = actions::onCopyManifest) { Text("Copy new URL") } },
            dismissButton = { TextButton(onClick = { portNoticeDismissed = true }) { Text("Got it") } })
    }
    DashboardBackground {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RelayHero(state.runtime, if (state.settings.lanAddress.isBlank()) "this computer" else "private LAN") {
            if (running) actions.onStop() else actions.onStart()
        }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Your providers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("One connection. Every match.", color = quiet, fontSize = 12.sp)
            }
            val batch = state.runtime.testBatch
            OutlinedButton(onClick = actions::onTestAll, enabled = running && !state.runtime.providerTestsBusy() && config.sources.any { it.enabled }, modifier = Modifier.testTag("test-all-providers")) {
                if (batch.running) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (batch.running) "Testing ${minOf(batch.completed + 1, batch.total)} / ${batch.total}" else "Test all providers")
            }
        }
        if (config.sources.isEmpty()) Text("Add a provider below to get started.", color = quiet)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileWidth = ((maxWidth - 6.dp - 12.dp * (config.sources.size - 1)) / config.sources.size).coerceIn(174.dp, 240.dp)
            LazyRow(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(3.dp)) {
                items(config.sources, key = { it.id }) { source -> ProviderTile(source, state.runtime, Modifier.width(tileWidth)) { testing = source } }
            }
        }
        Text("Stream Fetching is true when real media is fetched. Select a provider for test details.", color = quiet, fontSize = 11.sp)
        Surface(Modifier.fillMaxWidth(), color = panel, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connect Stremio", style = MaterialTheme.typography.titleLarge)
                Text("Set up once. Keep Futbolio running when you watch.", color = quiet)
                Selectable(state.manifest)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = running, onClick = actions::onInstall) { Text("Install in Stremio") }
                    OutlinedButton(onClick = actions::onCopyManifest) { Text("Copy manifest URL") }
                    OutlinedButton(enabled = running, onClick = actions::onInstallPage) { Text("Installation page") }
                }
                Text(if (state.settings.lanAddress.isBlank())
                    "Localhost mode uses the same token-free URL as the mobile app. Keep Futbolio running during playback."
                    else "This LAN URL includes a private access token. Share it only with trusted devices. Keep this computer awake during playback.")
                Text("For another device, choose a private LAN address under Network. Never expose the relay publicly.", color = quiet, fontSize = 12.sp)
             } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tab = 1 }) { Text("Provider settings") }
            OutlinedButton(onClick = { preferences = true }) { Text("Match settings") }
            OutlinedButton(onClick = { tab = 2; actions.onRefreshNetwork() }) { Text("Network settings") }
            OutlinedButton(onClick = { tab = 3 }) { Text("Diagnostics") }
        }
        Text("Private by design. No analytics, no video files on disk. Use only streams you are authorized to access.", color = quiet, fontSize = 11.sp)
    }
    }
    if (tab != 0) AlertDialog(onDismissRequest = { tab = 0 }, containerColor = panel,
        title = { Text(when (tab) { 1 -> "Provider settings"; 2 -> "Network settings"; else -> "Diagnostics" }) },
        text = {
        Column(Modifier.width(620.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (tab) {
            1 -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { tab = 0; adding = true }, enabled = config.sources.size < 8) { Text("Add provider") }
                    OutlinedButton(onClick = actions::onRefresh, enabled = running) { Text("Refresh") }
                    OutlinedButton(onClick = actions::onImport) { Text("Import JSON") }
                    OutlinedButton(onClick = actions::onExport) { Text("Export") }
                    OutlinedButton(onClick = { tab = 0; defaults = true }) { Text("Restore defaults") }
                }
                Text("Built-in adapters; editable URLs and bounded domain recovery.", color = quiet)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { config.sources.forEach { source ->
                    Surface(Modifier.fillMaxWidth(), color = Color(0xff18241c), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(source.url, color = Color(0xffa3b6ac))
                            val status = state.runtime.providers.find { it.id == source.id }
                            Text(if (!source.enabled) "Disabled" else if (status == null) "Not checked" else if (status.loading) "Refreshing…" else if (status.ok) "${status.count} listings${if (status.warning.isNotEmpty()) " · ${status.warning}" else ""}" else status.error.ifBlank { "Not checked" })
                            if (!status?.activeUrl.isNullOrBlank()) Text("Active: ${status?.activeUrl}", color = Color(0xffa3b6ac))
                        }
                        OutlinedButton(onClick = { tab = 0; testing = source }) { Text("Details") }
                        OutlinedButton(onClick = { tab = 0; edit = source }) { Text("Edit") }
                    } }
                } }
            }
            2 -> NetworkSettings(state, actions)
            3 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                Text("No raw stream URLs or access tokens are included.")
                OutlinedButton(onClick = actions::onCopyDiagnostics) { Text("Copy diagnostics") }
                Selectable(state.diagnosticsText)
            }
        }
    }
    }, confirmButton = { TextButton(onClick = { tab = 0 }) { Text("Close") } })
    if (adding || edit != null) ProviderDialog(edit, state.descriptors, { adding = false; edit = null }, actions::saveSource, actions::removeSource)
    testing?.let { source -> WorkbenchDialog(source, state.runtime, actions) { testing = null } }
    if (preferences) MatchPreferences(config.presentation, { preferences = false }, actions::savePresentation)
    if (defaults) {
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { defaults = false }, title = { Text("Restore default providers?") }, text = { Text(error.ifBlank { "Your current provider configuration will be replaced." }) },
            confirmButton = { Button(onClick = { scope.launch { val result = actions.restoreDefaults(); if (result.saved) defaults = false else error = result.error } }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { defaults = false }) { Text("Cancel") } })
    }
}

@Composable private fun Selectable(text: String) { androidx.compose.foundation.text.selection.SelectionContainer { Surface(shape = MaterialTheme.shapes.medium, color = panel) { Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) } } }

@Composable private fun NetworkSettings(state: DesktopUiState, actions: DesktopActions) {
    val settings = state.settings
    val editable = state.runtime.phase == RuntimePhase.STOPPED
    var address by remember(settings) { mutableStateOf(settings.lanAddress) }
    var port by remember(settings) { mutableStateOf(settings.port.toString()) }
    var regenerate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network hosting", style = MaterialTheme.typography.titleLarge)
        Text("Stop Futbolio before changing these settings. Installed URLs stop working when the address, port, or token changes.")
        (listOf("") + state.addresses).forEach { ip -> Row(Modifier.fillMaxWidth().clickable(enabled = editable) { address = ip }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(ip == address, { address = ip }, enabled = editable); Text(ip.ifBlank { "This computer only (127.0.0.1)" })
        } }
        OutlinedTextField(port, { port = it }, label = { Text("Port") }, enabled = editable)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(regenerate, { regenerate = it }, enabled = editable); Text("Replace access token (requires reinstalling the add-on)") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = editable, onClick = { scope.launch { val result = actions.saveNetwork(address, port, regenerate); error = result.error; if (result.saved) regenerate = false } }) { Text("Save network settings") }
    }
}

@Composable private fun ProviderDialog(existing: SourceConfig?, descriptors: List<ProviderDescriptor>, dismiss: () -> Unit,
    save: suspend (SourceConfig) -> SaveResult, remove: suspend (String) -> SaveResult) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: descriptors.first().kind) }
    var url by remember { mutableStateOf(existing?.url ?: "https://") }
    var api by remember { mutableStateOf(existing?.apiUrl.orEmpty()) }
    var data by remember { mutableStateOf(existing?.dataUrl.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var fallbacks by remember { mutableStateOf(existing?.fallbackUrls?.joinToString("\n").orEmpty()) }
    var window by remember { mutableStateOf((existing?.numberedDomainWindow ?: 0).toString()) }
    var zone by remember { mutableStateOf(existing?.timeZone ?: descriptors.first().defaultTimeZone) }
    var removing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "Add provider" else "Edit provider") }, text = {
        Column(Modifier.width(520.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.testTag("provider-name"))
            Row(Modifier.horizontalScroll(rememberScrollState())) { descriptors.forEach { descriptor -> FilterChip(kind == descriptor.kind, onClick = { kind = descriptor.kind; if (existing == null) zone = descriptor.defaultTimeZone }, label = { Text(descriptor.kind) }) } }
            OutlinedTextField(url, { url = it }, label = { Text("Website URL") }, singleLine = true, modifier = Modifier.testTag("provider-url"))
            OutlinedTextField(api, { api = it }, label = { Text("Optional API URL") }, singleLine = true)
            OutlinedTextField(data, { data = it }, label = { Text("Optional data-script URL") }, singleLine = true)
            OutlinedTextField(fallbacks, { fallbacks = it }, label = { Text("Approved fallback URLs (one per line)") }, minLines = 2)
            OutlinedTextField(window, { window = it }, label = { Text("Numbered-domain window (0 disables; max 3)") }, singleLine = true)
            OutlinedTextField(zone, { zone = it }, label = { Text("Source timezone (IANA)") }, singleLine = true)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("Enabled", Modifier.padding(8.dp)) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (existing != null) TextButton(onClick = { removing = true }) { Text("Remove provider") }
        }
    }, confirmButton = { Button(enabled = !saving, onClick = {
        val number = window.toIntOrNull()
        if (number == null) error = "Enter a numeric domain window" else scope.launch {
            saving = true
            try {
                val result = save(SourceConfig(existing?.id.orEmpty(), kind, name.trim(), url.trim(), api.trim(), data.trim(), enabled,
                    fallbacks.lines().map(String::trim).filter(String::isNotBlank), number, zone.trim()))
                if (result.saved) dismiss() else error = result.error
            } finally { saving = false }
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if (removing && existing != null) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove ${existing.name}?") },
        confirmButton = { Button(onClick = { scope.launch { val result = remove(existing.id); if (result.saved) dismiss() else { removing = false; error = result.error } } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
}

@Composable private fun WorkbenchDialog(source: SourceConfig, runtime: RuntimeSnapshot, actions: DesktopActions, dismiss: () -> Unit) {
    val report = runtime.reports[source.id]
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, containerColor = panel, title = { Text(source.name) }, text = {
        Column(Modifier.width(600.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PROVIDER OVERVIEW", color = emerald, fontSize = 11.sp)
            OutlinedButton(onClick = { actions.onTest(source.id) }, enabled = source.enabled && runtime.running && !runtime.providerTestsBusy()) { Text("Test This Provider") }
            app.futbolio.application.presentation.providerDetails(source, runtime).forEach { (label, value) ->
                Surface(color = Color(0xff18241c), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = emerald, style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Find another match/channel to test") }, singleLine = true)
            if (query.isNotBlank()) runtime.candidates[source.id].orEmpty().filter { it.title.contains(query, true) }.take(5).forEach { candidate ->
                TextButton(onClick = { actions.onTest(source.id, candidate.id); query = "" }) { Text("Test ${candidate.title}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { actions.onCopyReport(source.id) }) { Text("Copy report") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable private fun MatchPreferences(options: PresentationConfig, dismiss: () -> Unit, save: suspend (PresentationConfig) -> SaveResult) {
    var zone by remember { mutableStateOf(options.displayTimeZone) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Match settings") }, text = { Column {
        OutlinedTextField(zone, { zone = it }, label = { Text("Display timezone (IANA)") })
        Text("Curated competitions only: live and starting within 24 hours. ESPN scores, match minutes and halftime status are added when matched; confirmed ended matches are hidden. Otherwise status is estimated and matches expire after 2 hours, or 3 hours for cups/tournaments. Advanced aliases can be imported in JSON.")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = { scope.launch { val result = save(options.copy(scoresEnabled = false, hideFinished = true, showHalftime = true, displayTimeZone = zone.trim())); if (result.saved) dismiss() else error = result.error } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
