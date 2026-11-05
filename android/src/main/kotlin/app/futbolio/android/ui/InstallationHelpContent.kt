package app.futbolio.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Compact steps sized to their content, rather than a fixed-height scrolling text pane. */
@Composable internal fun InstallationHelpContent(television: Boolean, manifest: String, onCopy: () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 600
    val steps = if (television) listOf(
        "Start the relay in Futbolio.",
        "Copy the add-on URL below.",
        "Open Stremio on this TV.",
        "Go to Add-ons → Add Addon, paste the URL, and confirm.",
        "Keep Futbolio running while you watch in Stremio.",
    ) else listOf(
        "Start the relay in Futbolio.",
        "Copy the add-on URL below.",
        "Open web.stremio.com in your mobile browser and sign in.",
        "Go to Add-ons → Add Addon, paste the URL, and confirm.",
        "Watch in the Stremio mobile app using the same account. Keep Futbolio running.",
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
            steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            }
        }
        SelectionContainer { Text(manifest, color = Color(0xff80c77d), style = MaterialTheme.typography.bodySmall) }
        TvAction("Copy URL", modifier = Modifier.fillMaxWidth(), icon = SolidIcon.Copy, onClick = onCopy)
    }
}
