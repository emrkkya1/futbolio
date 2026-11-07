package app.futbolio.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
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

internal val theme = darkColorScheme(primary = lime, secondary = lavender, background = backdrop,
    surface = panel, surfaceVariant = Color(0xff303030), onPrimary = Color.White,
    onBackground = Color.White, onSurface = Color.White, outline = edge)

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
    var settings by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var savingTimes by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var portNoticeDismissed by remember(state.settings.port) { mutableStateOf(false) }
    if (running && state.settings.port != 7000 && !portNoticeDismissed) {
        AlertDialog(onDismissRequest = { portNoticeDismissed = true }, title = { Text("Futbolio port changed") },
            text = { Text("Futbolio is using port ${state.settings.port}. If you already added the add-on in Stremio with port 7000 or another old port, remove it and add it again using the new URL: ${state.manifest}") },
            confirmButton = { Button(onClick = actions::onCopyManifest) { Text("Copy new URL") } },
            dismissButton = { TextButton(onClick = { portNoticeDismissed = true }) { Text("Got it") } })
    }
    DashboardBackground {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    BrandHeader()
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp),
                        verticalArrangement = Arrangement.Center) {
                        RelayHero(state.runtime) { if (running) actions.onStop() else actions.onStart() }
                        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopAction("How to Install", icon = SolidIcon.Help) { help = true }
                        DesktopAction("Settings", icon = SolidIcon.Settings) { settings = true }
                    }
                }
            }
            val sources: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Stream sources", style = MaterialTheme.typography.titleLarge)
                    if (config.sources.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text("No stream sources configured.", color = quiet)
                    } else Box(Modifier.weight(1f).fillMaxWidth()) {
                        val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
                        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = scroll, contentPadding = PaddingValues(4.dp)) {
                            items(config.sources, key = { it.id }) { source ->
                                ProviderTile(source, state.runtime, Modifier.fillMaxWidth()) { testing = source }
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = edge)
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                    val batch = state.runtime.testBatch
                    DesktopAction(if (batch.running) "Testing ${minOf(batch.completed + 1, batch.total)} / ${batch.total}" else "Test Providers",
                        running && !state.runtime.providerTestsBusy() && config.sources.any { it.enabled },
                        Modifier.fillMaxWidth().testTag("test-all-providers"), icon = SolidIcon.Whistle, onClick = actions::onTestAll)
                }
            }
            if (maxWidth >= 760.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                controls(Modifier.weight(1.45f).fillMaxHeight())
                VerticalDivider(color = edge)
                sources(Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                controls(Modifier.fillMaxWidth().height(490.dp))
                HorizontalDivider(color = edge)
                sources(Modifier.fillMaxWidth().height(560.dp))
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("How to Install") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("1. Start Futbolio and keep it running while you watch.")
            Text("2. Open Stremio on this computer and install the add-on using the URL below.")
            Selectable(state.manifest)
            DesktopAction("Copy URL", modifier = Modifier.fillMaxWidth(), onClick = actions::onCopyManifest)
            DesktopAction("Install in Stremio", enabled = running, modifier = Modifier.fillMaxWidth(), onClick = actions::onInstall)
            Text(if (state.settings.lanAddress.isBlank()) "This URL works only on this computer. For another device, select a private LAN address in Network settings."
                else "This URL contains a private access token. Share it only with trusted devices on your LAN; never expose this port publicly.", color = quiet)
        }
    }, confirmButton = { DesktopAction("Close", icon = SolidIcon.Close) { help = false } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopAction("Diagnostics") { settings = false; tab = 3 }
            DesktopAction("Provider settings") { settings = false; tab = 1 }
            DesktopAction("Network settings") { settings = false; tab = 2; actions.onRefreshNetwork() }
            DesktopAction("Match settings") { settings = false; preferences = true }
            Text("Times and Scores", style = MaterialTheme.typography.titleSmall)
            PointedOnOffSwitch(config.presentation.showTimesAndScores, enabled = !savingTimes) { enabled ->
                    scope.launch {
                        savingTimes = true
                        try {
                            val result = actions.savePresentation(config.presentation.copy(showTimesAndScores = enabled))
                            settingsError = result.error
                        } finally { savingTimes = false }
                    }
            }
            if (settingsError.isNotBlank()) Text(settingsError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { DesktopAction("Close", icon = SolidIcon.Close) { settings = false } })
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
    val health = app.futbolio.application.presentation.providerCardState(source, runtime)
    val connected = health.connected.tone == app.futbolio.application.presentation.HealthTone.GOOD
    val status = runtime.providers.firstOrNull { it.id == source.id }
    val count = status?.takeIf { it.ok || it.count > 0 }?.count
        ?: runtime.reports[source.id]?.catalogCount?.takeIf { it > 0 }
    val fetching = health.fetching.tone == app.futbolio.application.presentation.HealthTone.GOOD
    AlertDialog(onDismissRequest = dismiss, containerColor = panel, title = { Text(source.name) }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf("Available" to if (connected) "Yes" else "No",
                "Catalog" to (count?.let { "$it Events" } ?: health.catalog.text),
                "Live Streams" to if (fetching) "Working" else health.fetching.text).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, Modifier.weight(1f), color = lavender, style = MaterialTheme.typography.titleSmall)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = { DesktopAction("Close", icon = SolidIcon.Close, onClick = dismiss) }, dismissButton = {
        DesktopAction("Test Provider", source.enabled && runtime.running && !runtime.providerTestsBusy(), icon = SolidIcon.Whistle) { actions.onTest(source.id) }
    })
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
