package app.futbolio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.futbolio.engine.*
import app.futbolio.application.api.FutbolioConfig
import app.futbolio.engine.api.Configurations
import app.futbolio.desktop.ui.DesktopScreen
import app.futbolio.desktop.ui.theme
import app.futbolio.desktop.ui.futbolioTypography
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.futbolio.application.api.*
import app.futbolio.desktop.presentation.DesktopActions
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun relayPreparationFillsDiamondAndRemainsStoppableUntilFirstMatchReady() {
        var runtime by mutableStateOf(RuntimeSnapshot(RuntimePhase.RUNNING, preparation = RelayPreparation(.5f)))
        var stops = 0
        compose.setContent {
            MaterialTheme(colorScheme = theme, typography = futbolioTypography) {
                Surface { Box(Modifier.size(720.dp, 280.dp)) {
                    app.futbolio.desktop.ui.RelayHero(runtime) { stops++ }
                } }
            }
        }
        compose.onAllNodesWithText("Relay is starting, please wait…").assertCountEquals(2)
        compose.onNodeWithText("Relay is live").assertDoesNotExist()
        compose.onNodeWithTag("relay-toggle").assertIsEnabled().performClick()
        assertEquals(1, stops)
        screenshot("desktop-relay-preparing")
        compose.runOnIdle { runtime = runtime.copy(preparation = RelayPreparation(1f, true)) }
        compose.onNodeWithText("Relay is live").assertExists()
        compose.onNodeWithText("Stop Futbolio").assertExists()
        screenshot("desktop-relay-ready")
        compose.runOnIdle { runtime = RuntimeSnapshot() }
        compose.onNodeWithText("Relay is stopped").assertExists()
        compose.onNodeWithText("Start Futbolio").assertExists()
    }
    @Test fun providerCrudAndNavigation() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-ui-test"))
        try {
            runBlocking { host.saveConfig(FutbolioConfig(sources = Configurations.sources().map { it.copy(enabled = false) })) }
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) { Surface { DesktopScreen(state, host) } } }
            compose.onNodeWithText("Start Futbolio").assertExists()
            screenshot("desktop-connect")
            compose.onNodeWithText("How to Install").performClick()
            compose.onNodeWithText("Copy URL").assertExists()
            compose.onNodeWithText("Close").performClick()
            openSetting("Provider settings")
            compose.onNodeWithText("Add provider").assertExists()
            compose.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
            compose.onNodeWithTag("provider-name").performTextReplacement("PPV renamed")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { host.configuration.value.config.sources.any { it.name == "PPV renamed" } }
            compose.onAllNodesWithText("PPV renamed").onFirst().assertExists()
            screenshot("desktop-providers")
            openSetting("Provider settings")
            compose.onNodeWithText("Add provider").performScrollTo().performClick()
            compose.onNodeWithTag("provider-name").performTextReplacement("New test source")
            compose.onNodeWithTag("provider-url").performTextReplacement("https://fixture.example/")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            val expectedCount = Configurations.sources().size + 1
            compose.waitUntil(5000) { host.configuration.value.config.sources.size == expectedCount }
            assertEquals(expectedCount, host.configuration.value.config.sources.size)
            openSetting("Network settings")
            compose.onNodeWithText("Network hosting").assertExists()
            screenshot("desktop-network")
            compose.onNodeWithText("Close").performClick()
            openSetting("Diagnostics")
            compose.onNodeWithText("Copy diagnostics").assertExists()
            assertTrue(host.error.value.isBlank())
        } finally { host.close() }
    }
    private fun screenshot(name: String) {
        val file = File("build/reports/screenshots/$name.png"); file.parentFile.mkdirs()
        val roots = compose.onAllNodes(isRoot())
        ImageIO.write(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(), "png", file)
    }
    private fun openSetting(name: String) {
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText(name, useUnmergedTree = true).performScrollTo().performClick()
    }

    @Test fun failedSaveKeepsDialogAndEditedInput() {
        val directory = Files.createTempDirectory("futbolio-ui-save-failure")
        val host = DesktopHost(directory)
        try {
            Files.createDirectory(directory.resolve("providers.json"))
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) { Surface { DesktopScreen(state, host) } } }
            openSetting("Provider settings")
            compose.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
            compose.onNodeWithTag("provider-name").performTextReplacement("Unsaved change")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Could not save settings. Check file permissions and retry.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit provider").assertExists()
            compose.onNodeWithTag("provider-name").assertTextContains("Unsaved change")
            assertTrue(host.configuration.value.config.sources.none { it.name == "Unsaved change" })
        } finally { host.close() }
    }

    @Test fun dashboardHasOneRelayToggleAndOneBatchTestAction() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-dashboard-ui"))
        try {
            var runtime by mutableStateOf(RuntimeSnapshot())
            var starts = 0; var stops = 0; var batches = 0
            var testedProvider = ""
            val actions = object : DesktopActions by host {
                override fun onStart() { starts++; runtime = RuntimeSnapshot(RuntimePhase.RUNNING,
                    providers = host.configuration.value.config.sources.map { ProviderStatus(it.id, it.name, it.enabled, ok = true, count = 24) },
                    preparation = RelayPreparation(1f, true)) }
                override fun onStop() { stops++; runtime = RuntimeSnapshot() }
                override fun onTestAll() { batches++ }
                override fun onTest(sourceId: String, listingId: String?) { testedProvider = sourceId }
            }
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) { Surface { DesktopScreen(state.copy(runtime = runtime), actions) } } }
            compose.onNodeWithTag("relay-toggle").assertHasClickAction().performClick()
            compose.onAllNodesWithText("Stop Futbolio").assertCountEquals(1)
            compose.onAllNodesWithText("Start Futbolio").assertCountEquals(0)
            compose.onNodeWithTag("test-all-providers").performClick()
            assertEquals(1, starts); assertEquals(1, batches)
            val source = host.configuration.value.config.sources.first()
            compose.onNodeWithTag("provider-${source.id}").performClick()
            compose.onNodeWithText("Available").assertExists()
            compose.onNodeWithText("Live Streams").assertExists()
            compose.onNodeWithText("Test Provider").performClick()
            assertEquals(source.id, testedProvider)
            compose.onNodeWithText("Close").performClick()
            screenshot("desktop-dashboard-live")
            compose.onNodeWithTag("relay-toggle").performClick()
            compose.onNodeWithText("Start Futbolio").assertExists()
            assertEquals(1, stops)
        } finally { host.close() }
    }

    @Test fun timesAndScoresCanBeChangedFromTvStyleSettings() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-times-ui"))
        try {
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) { Surface { DesktopScreen(state, host) } } }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithContentDescription("Times and Scores off").performScrollTo().performClick()
            compose.waitUntil(5000) { !host.configuration.value.config.presentation.showTimesAndScores }
            compose.onNodeWithContentDescription("Times and Scores off").assertIsSelected()
            screenshot("desktop-settings")
        } finally { host.close() }
    }

    @Test fun narrowWindowKeepsControlsAndScrollableSourcesReachable() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-narrow-ui"))
        try {
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) {
                Surface { Box(Modifier.size(640.dp, 720.dp)) { DesktopScreen(state, host) } }
            } }
            compose.onNodeWithTag("relay-toggle").assertIsDisplayed()
            compose.onNodeWithTag("test-all-providers").performScrollTo().assertIsDisplayed()
            screenshot("desktop-narrow")
        } finally { host.close() }
    }

    @Test fun portWarningAndHelpUseTheActualSelectedEndpoint() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-port-ui"))
        val selected = DesktopSettings(port = 7001)
        var copied = ""
        val actions = object : DesktopActions by host { override fun onCopyManifest() { copied = selected.host().manifest } }
        try {
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme, typography = futbolioTypography) {
                Surface { DesktopScreen(state.copy(settings = selected, runtime = RuntimeSnapshot(RuntimePhase.RUNNING)), actions) }
            } }
            compose.onNodeWithText("Futbolio port changed").assertExists()
            compose.onNodeWithText("Copy new URL").performClick()
            assertEquals("http://127.0.0.1:7001/manifest.json", copied)
            compose.onNodeWithText("Got it").performClick()
            compose.onNodeWithText("How to Install").performClick()
            compose.onNodeWithText(selected.host().manifest).assertExists()
        } finally { host.close() }
    }
}
