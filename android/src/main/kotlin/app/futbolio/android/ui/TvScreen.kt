package app.futbolio.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import app.futbolio.application.presentation.providerTestsBusy
import app.futbolio.android.presentation.*
import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.coroutines.launch

internal val tvTheme = darkColorScheme(primary = Color(0xffb4ff62), secondary = Color(0xff1ee69b), background = Color(0xff040705),
    surface = Color(0xff101713), surfaceVariant = Color(0xff18241c), onPrimary = Color(0xff05160c), onSurface = Color(0xfff1f8f3), outline = Color(0xff355441))
@Composable private fun isTelevision() = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

@Composable internal fun TvAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (!isTelevision()) {
        Button(onClick, enabled = enabled, modifier = modifier.padding(end = 8.dp, bottom = 8.dp).heightIn(min = 48.dp)) { Text(text) }
    } else androidx.tv.material3.Button(onClick, enabled = enabled,
        colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = panel, contentColor = Color(0xfff1f8f3),
            focusedContainerColor = lime, focusedContentColor = Color(0xff05160c)),
        modifier = modifier.padding(end = 12.dp, bottom = 8.dp)) { androidx.tv.material3.Text(text) }
}

/** TV/touch layout consumes values and actions, never a service, engine, settings store or HTTP object. */
@Composable internal fun TvScreen(state: AndroidUiState, actions: TvActions) {
    val config = state.config
    val runtime = state.runtime
    val television = isTelevision()
    val relayFocus = remember { FocusRequester() }
    var restoreRelayFocus by remember { mutableStateOf(false) }
    LaunchedEffect(television) { if (television) relayFocus.requestFocus() }
    LaunchedEffect(runtime.phase) {
        if (television && restoreRelayFocus && runtime.phase in listOf(RuntimePhase.STOPPED, RuntimePhase.RUNNING)) {
            relayFocus.requestFocus(); restoreRelayFocus = false
        }
    }
    var edit by remember { mutableStateOf<SourceConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var defaults by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<SourceConfig?>(null) }
    var preferences by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DashboardBackground {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (television) 40.dp else 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RelayHero(runtime, Modifier.focusRequester(relayFocus)) {
            restoreRelayFocus = true
            if (runtime.running) actions.stop() else actions.start()
        }
        if (runtime.error.isNotBlank()) Text(runtime.error, color = MaterialTheme.colorScheme.error)
        if (state.configuration.applyError.isNotBlank()) Text(state.configuration.applyError, color = MaterialTheme.colorScheme.error)
        if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Your providers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("One connection. Every match.", color = quiet, fontSize = 12.sp)
            }
            val batch = runtime.testBatch
            TvAction(if (batch.running) "Testing ${minOf(batch.completed + 1, batch.total)} / ${batch.total}" else "Test all providers",
                runtime.running && !runtime.providerTestsBusy() && config.sources.any { it.enabled }, Modifier.testTag("test-all-providers"), actions::testAll)
        }
        if (config.sources.isEmpty()) Text("Add a provider in Provider settings to get started.", color = quiet)
        else LazyRow(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(3.dp)) {
            items(config.sources, key = { it.id }) { source -> ProviderTile(source, runtime, Modifier.width(174.dp)) { testing = source } }
        }
        Text("Stream Fetching is true when real media is fetched. Select a provider for test details.", color = quiet, fontSize = 11.sp)
        Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect Stremio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Set up once. Keep Futbolio running when you watch.", color = quiet)
                SelectionContainer { Text(state.manifest, color = lime) }
                FlowRow { TvAction("Install / open Stremio", runtime.running, onClick = actions::install); TvAction("Copy URL", onClick = actions::copyManifest); TvAction("Installation help", onClick = { help = true }) }
                Text("Same-device relay. No PC needed during playback.", color = quiet, fontSize = 12.sp)
            }
        }
        FlowRow {
            TvAction("Provider settings") { management = true }
            TvAction("Match settings") { preferences = true }
            TvAction("Diagnostics") { diagnostics = true }
        }
        Text("Private by design. No analytics, no video files on disk. Use only streams you are authorized to access.", color = quiet, fontSize = 11.sp)
    }
    }
    if (adding || edit != null) ProviderEditor(edit, state.descriptors, { adding = false; edit = null }, actions::saveSource, actions::removeSource)
    testing?.let { source -> TestReportDialog(source, runtime, actions) { testing = null } }
    if (preferences) MatchPreferences(config.presentation, { preferences = false }, actions::savePresentation)
    if (management) AlertDialog(onDismissRequest = { management = false }, title = { Text("Provider settings") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.sources.forEach { source -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, Modifier.weight(1f)); TvAction("Edit") { management = false; edit = source }
            } }
            FlowRow {
                TvAction("Add provider", config.sources.size < 8) { management = false; adding = true }
                TvAction("Refresh catalogs", runtime.running, onClick = actions::refresh)
                TvAction("Import JSON", onClick = actions::importProviders)
                TvAction("Export JSON", onClick = actions::exportProviders)
                TvAction("Restore defaults") { management = false; defaults = true }
            }
        }
    }, confirmButton = { TvAction("Close") { management = false } })
    if (help) InfoDialog("Stremio installation", "Start Futbolio, then try Install / open Stremio. TV versions differ; some do not support add-on deep links or URL installation.\n\nYou may need to install this exact manifest URL through the same Stremio account on desktop, while a compatible local server is reachable on that desktop. On the TV, 127.0.0.1 means this TV, not your PC.\n\nFutbolio cannot silently install add-ons into another app or account. No PC is needed during playback once installed.") { help = false }
    if (defaults) {
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { defaults = false }, title = { Text("Restore defaults?") }, text = { Text(error.ifBlank { "Replace your provider configuration with the bundled defaults?" }) },
            confirmButton = { TvAction("Restore") { scope.launch { val result = actions.restoreDefaults(); if (result.saved) defaults = false else error = result.error } } },
            dismissButton = { TvAction("Cancel") { defaults = false } })
    }
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text("Diagnostics (no stream URLs)") },
        text = { SelectionContainer { Text(state.diagnostics, Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TvAction("Copy", onClick = actions::copyDiagnostics) }, dismissButton = { TvAction("Close") { diagnostics = false } })
}

@Composable private fun InfoDialog(title: String, text: String, dismiss: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text, Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TvAction("Close", onClick = dismiss) }) }

@Composable private fun ProviderEditor(existing: SourceConfig?, descriptors: List<ProviderDescriptor>, dismiss: () -> Unit,
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
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "Add provider" else "Edit provider") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)
            Column { descriptors.forEach { descriptor -> TvAction(if (kind == descriptor.kind) "✓ ${descriptor.kind}" else descriptor.kind) { kind = descriptor.kind; if (existing == null) zone = descriptor.defaultTimeZone } } }
            OutlinedTextField(url, { url = it }, label = { Text("Website URL") }, singleLine = true)
            OutlinedTextField(api, { api = it }, label = { Text("Optional API URL") }, singleLine = true)
            OutlinedTextField(data, { data = it }, label = { Text("Optional data-script URL") }, singleLine = true)
            OutlinedTextField(fallbacks, { fallbacks = it }, label = { Text("Approved fallback URLs (one per line)") }, minLines = 2)
            OutlinedTextField(window, { window = it }, label = { Text("Numbered-domain window (0 disables, max 3)") }, singleLine = true)
            OutlinedTextField(zone, { zone = it }, label = { Text("Source timezone (IANA)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("Enabled", Modifier.padding(8.dp)) }
            if (existing != null) TvAction("Remove provider") { removing = true }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TvAction("Save", !saving) {
        val number = window.toIntOrNull()
        if (number == null) error = "Enter a numeric domain window" else scope.launch {
            saving = true
            try {
                val result = save(SourceConfig(existing?.id.orEmpty(), kind, name.trim(), url.trim(), api.trim(), data.trim(), enabled,
                    fallbacks.lines().map(String::trim).filter(String::isNotBlank), number, zone.trim()))
                if (result.saved) dismiss() else error = result.error
            } finally { saving = false }
        }
    } }, dismissButton = { TvAction("Cancel", onClick = dismiss) })
    if (removing && existing != null) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove ${existing.name}?") },
        confirmButton = { TvAction("Remove") { scope.launch { val result = remove(existing.id); if (result.saved) dismiss() else { removing = false; error = result.error } } } },
        dismissButton = { TvAction("Cancel") { removing = false } })
}

@Composable private fun TestReportDialog(source: SourceConfig, runtime: RuntimeSnapshot, actions: TvActions, dismiss: () -> Unit) {
    val report = runtime.reports[source.id]
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, containerColor = panel, title = { Text(source.name) }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PROVIDER OVERVIEW", color = emerald, fontSize = 11.sp)
            TvAction("Test This Provider", source.enabled && runtime.running && !runtime.providerTestsBusy()) { actions.test(source.id) }
            app.futbolio.application.presentation.providerDetails(source, runtime).forEach { (label, value) ->
                Surface(color = Color(0xff18241c), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = emerald, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Find another match/channel to test") }, singleLine = true)
            if (query.isNotBlank()) runtime.candidates[source.id].orEmpty().filter { it.title.contains(query, true) }.take(5).forEach { candidate ->
                TvAction("Test ${candidate.title}") { actions.test(source.id, candidate.id); query = "" }
            }
        }
    }, confirmButton = { TvAction("Copy report") { actions.copyReport(source.id) } }, dismissButton = { TvAction("Close", onClick = dismiss) })
}

@Composable private fun MatchPreferences(options: PresentationConfig, dismiss: () -> Unit, save: suspend (PresentationConfig) -> SaveResult) {
    var zone by remember { mutableStateOf(options.displayTimeZone) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Match settings") }, text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(zone, { zone = it }, label = { Text("Display timezone (IANA)") })
        Text("Curated competitions only: live and starting within 24 hours. ESPN scores, match minutes and halftime status are added when matched; confirmed ended matches are hidden. Otherwise status is estimated and matches expire after 2 hours, or 3 hours for cups/tournaments. Advanced aliases can be imported in JSON.")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TvAction("Save") { scope.launch { val result = save(options.copy(scoresEnabled = false, hideFinished = true, showHalftime = true, displayTimeZone = zone.trim())); if (result.saved) dismiss() else error = result.error } } },
        dismissButton = { TvAction("Cancel", onClick = dismiss) })
}
