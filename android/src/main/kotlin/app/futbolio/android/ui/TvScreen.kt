package app.futbolio.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import app.futbolio.application.presentation.providerTestsBusy
import app.futbolio.application.presentation.providerCardState
import app.futbolio.application.presentation.HealthTone
import app.futbolio.android.presentation.*
import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

internal val tvTheme = darkColorScheme(primary = lime, secondary = lavender, background = backdrop,
    surface = panel, surfaceVariant = Color(0xff303030), onPrimary = Color.White,
    onBackground = Color.White, onSurface = Color.White, outline = edge)
@Composable private fun isTelevision() = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
@Composable internal fun focusOutlineColor() = if (isTelevision()) lavender else Color.White

@Composable internal fun TvAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    icon: SolidIcon? = null, compact: Boolean = LocalDialogFooter.current, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { SolidIcon(icon, Modifier.size(if (compact) 18.dp else 22.dp)); Spacer(Modifier.width(if (compact) 8.dp else 10.dp)) }
        Text(text, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
    if (!isTelevision()) {
        Button(onClick, enabled = enabled, shape = pointedButton, contentPadding = PaddingValues(horizontal = if (compact) 20.dp else 30.dp, vertical = 12.dp),
            modifier = modifier.then(LocalDialogActionModifier.current).heightIn(min = 48.dp), content = content)
    } else androidx.tv.material3.Button(onClick, enabled = enabled,
        shape = androidx.tv.material3.ButtonDefaults.shape(shape = pointedButton),
        border = androidx.tv.material3.ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, lavender), shape = pointedButton),
            focusedDisabledBorder = androidx.tv.material3.Border(BorderStroke(3.dp, lavender), shape = pointedButton)),
        contentPadding = PaddingValues(horizontal = if (compact) 20.dp else 30.dp, vertical = 12.dp),
        colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = lime, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = backdrop),
        modifier = modifier.then(LocalDialogActionModifier.current).heightIn(min = 48.dp), content = {
            CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) { content() }
        })
}

/** TV/touch layout consumes values and actions, never a service, engine, settings store or HTTP object. */
@Composable internal fun TvScreen(state: AndroidUiState, actions: TvActions, onCheckUpdates: () -> Unit = {},
    updateAvailable: Boolean = false, onOpenUpdate: () -> Unit = {}) {
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
    var help by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<SourceConfig?>(null) }
    var settings by remember { mutableStateOf(false) }
    var savingTimes by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    DashboardBackground {
        if (!television) MobileDashboard(state, actions, onToggle = {
            if (runtime.running) actions.stop() else actions.start()
        }, onProvider = { testing = it }, onHelp = { help = true }, onSettings = { settings = true },
            onOpenUpdate = if (updateAvailable) onOpenUpdate else null)
        else BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            val wide = maxWidth >= 760.dp
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        BrandHeader()
                        if (updateAvailable) Box(Modifier.align(Alignment.TopEnd)) { UpdateAvailableAction(onOpenUpdate) }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp),
                        verticalArrangement = Arrangement.Center) {
                        RelayHero(runtime, Modifier.focusRequester(relayFocus)) {
                            restoreRelayFocus = true
                            if (runtime.running) actions.stop() else actions.start()
                        }
                        if (runtime.error.isNotBlank()) Text(runtime.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (state.configuration.applyError.isNotBlank()) Text(state.configuration.applyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (state.message.isNotBlank()) Text(state.message, color = quiet, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvAction("How to Install", icon = SolidIcon.Help) { help = true }
                        TvAction("Settings", icon = SolidIcon.Settings) { settings = true }
                    }
                }
            }
            val sources: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SolidIcon(SolidIcon.Sources, Modifier.size(26.dp), lavender)
                        Text("Stream sources", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    }
                    if (config.sources.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text("No stream sources configured.", color = quiet)
                    } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                        items(config.sources, key = { it.id }) { source ->
                            ProviderTile(source, runtime, Modifier.fillMaxWidth()) { testing = source }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = edge)
                        }
                    }
                    val batch = runtime.testBatch
                    TvAction(if (batch.running) "Testing ${minOf(batch.completed + 1, batch.total)} / ${batch.total}" else "Test Providers",
                        runtime.running && !runtime.providerTestsBusy() && config.sources.any { it.enabled },
                        Modifier.fillMaxWidth().testTag("test-all-providers"), icon = SolidIcon.Whistle, onClick = actions::testAll)
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
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
    if (settings) AppDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction("Diagnostics", icon = SolidIcon.Sources) { settings = false; diagnostics = true }
            TvAction("Check for updates", icon = SolidIcon.Install) { settings = false; onCheckUpdates() }
            Text("Times and Scores", style = MaterialTheme.typography.titleSmall)
            PointedOnOffSwitch(config.presentation.showTimesAndScores, enabled = !savingTimes) { enabled ->
                scope.launch {
                    savingTimes = true
                    settingsError = ""
                    try {
                        val result = actions.savePresentation(config.presentation.copy(showTimesAndScores = enabled))
                        if (!result.saved) settingsError = result.error
                    } finally { savingTimes = false }
                }
            }
            if (settingsError.isNotBlank()) Text(settingsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvAction("Close", icon = SolidIcon.Close) { settings = false } })
    testing?.let { source -> TestReportDialog(source, runtime, actions) { testing = null } }
    if (help) AppDialog(onDismissRequest = { help = false }, title = { Text("How to Install") }, text = {
        InstallationHelpContent(television, state.manifest, actions::copyManifest)
    }, confirmButton = { TvAction("Close", icon = SolidIcon.Close) { help = false } })
    if (diagnostics) AppDialog(onDismissRequest = { diagnostics = false }, title = { Text("Diagnostics (no stream URLs)") },
        text = { SelectionContainer { Text(state.diagnostics, Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TvAction("Copy", onClick = actions::copyDiagnostics) }, dismissButton = { TvAction("Close", icon = SolidIcon.Close) { diagnostics = false } })
}

@Composable private fun TestReportDialog(source: SourceConfig, runtime: RuntimeSnapshot, actions: TvActions, dismiss: () -> Unit) {
    val health = providerCardState(source, runtime)
    val connected = health.connected.tone == HealthTone.GOOD
    val status = runtime.providers.firstOrNull { it.id == source.id }
    val count = status?.takeIf { it.ok || it.count > 0 }?.count
        ?: runtime.reports[source.id]?.catalogCount?.takeIf { it > 0 }
    val fetching = health.fetching.tone == HealthTone.GOOD
    AppDialog(onDismissRequest = dismiss, containerColor = panel, title = {
        Text(source.name, style = MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Available", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = lavender)
                Text(if (connected) "Yes" else "No", style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) lime else Color.White)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Catalog", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = lavender)
                Text(count?.let { "$it Events" } ?: health.catalog.text, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Live Streams", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = lavender)
                Text(if (fetching) "Working" else health.fetching.text, style = MaterialTheme.typography.bodyMedium,
                    color = if (fetching) lime else Color.White)
            }
        }
    }, confirmButton = { TvAction("Close", icon = SolidIcon.Close, onClick = dismiss) }, dismissButton = {
        TvAction("Test Provider", source.enabled && runtime.running && !runtime.providerTestsBusy(), icon = SolidIcon.Whistle) { actions.test(source.id) }
    })
}
