package app.futbolio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.futbolio.engine.*
import app.futbolio.application.api.FutbolioConfig
import app.futbolio.engine.api.Configurations
import app.futbolio.desktop.ui.DesktopScreen
import app.futbolio.desktop.ui.theme
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
    @Test fun providerCrudAndNavigation() {
        val host = DesktopHost(Files.createTempDirectory("futbolio-ui-test"))
        try {
            runBlocking { host.saveConfig(FutbolioConfig(sources = Configurations.sources().map { it.copy(enabled = false) })) }
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme) { Surface { DesktopScreen(state, host) } } }
            compose.onNodeWithText("Ready for\nkick-off.").assertExists()
            compose.onNodeWithText("Copy manifest URL").assertExists()
            screenshot("desktop-connect")
            compose.onNodeWithText("Provider settings", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Add provider").assertExists()
            compose.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
            compose.onNodeWithTag("provider-name").performTextReplacement("PPV renamed")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { host.configuration.value.config.sources.any { it.name == "PPV renamed" } }
            compose.onAllNodesWithText("PPV renamed").onFirst().assertExists()
            screenshot("desktop-providers")
            compose.onNodeWithText("Provider settings", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Add provider").performScrollTo().performClick()
            compose.onNodeWithTag("provider-name").performTextReplacement("New test source")
            compose.onNodeWithTag("provider-url").performTextReplacement("https://fixture.example/")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            val expectedCount = Configurations.sources().size + 1
            compose.waitUntil(5000) { host.configuration.value.config.sources.size == expectedCount }
            assertEquals(expectedCount, host.configuration.value.config.sources.size)
            compose.onNodeWithText("Network settings", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Network hosting").assertExists()
            screenshot("desktop-network")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Diagnostics", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Copy diagnostics").assertExists()
            assertTrue(host.error.value.isBlank())
        } finally { host.close() }
    }
    private fun screenshot(name: String) {
        val file = File("build/reports/screenshots/$name.png"); file.parentFile.mkdirs()
        val roots = compose.onAllNodes(isRoot())
        ImageIO.write(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage(), "png", file)
    }

    @Test fun failedSaveKeepsDialogAndEditedInput() {
        val directory = Files.createTempDirectory("futbolio-ui-save-failure")
        val host = DesktopHost(directory)
        try {
            Files.createDirectory(directory.resolve("providers.json"))
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme) { Surface { DesktopScreen(state, host) } } }
            compose.onNodeWithText("Provider settings", useUnmergedTree = true).performScrollTo().performClick()
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
                    providers = host.configuration.value.config.sources.map { ProviderStatus(it.id, it.name, it.enabled, ok = true, count = 24) }) }
                override fun onStop() { stops++; runtime = RuntimeSnapshot() }
                override fun onTestAll() { batches++ }
                override fun onTest(sourceId: String, listingId: String?) { testedProvider = sourceId }
            }
            compose.setContent { val state by host.ui.collectAsState(); MaterialTheme(colorScheme = theme) { Surface { DesktopScreen(state.copy(runtime = runtime), actions) } } }
            compose.onNodeWithTag("relay-toggle").assertHasClickAction().performClick()
            compose.onAllNodesWithText("Stop relay").assertCountEquals(1)
            compose.onAllNodesWithText("Start relay").assertCountEquals(0)
            compose.onNodeWithTag("test-all-providers").performScrollTo().performClick()
            assertEquals(1, starts); assertEquals(1, batches)
            val source = host.configuration.value.config.sources.first()
            compose.onNodeWithTag("provider-${source.id}").performClick()
            compose.onNodeWithText("Configured website").assertExists()
            compose.onNodeWithText("Test This Provider").performClick()
            assertEquals(source.id, testedProvider)
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("relay-toggle").performScrollTo()
            screenshot("desktop-dashboard-live")
            compose.onNodeWithTag("relay-toggle").performClick()
            compose.onNodeWithText("Start relay").assertExists()
            assertEquals(1, stops)
        } finally { host.close() }
    }
}
