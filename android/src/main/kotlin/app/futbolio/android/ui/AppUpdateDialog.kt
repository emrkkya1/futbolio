package app.futbolio.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.futbolio.android.updates.AppUpdateState

@Composable internal fun UpdateAvailableAction(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusColor = focusOutlineColor()
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag("update-available")
        .semantics { contentDescription = "Update available. Open update details" }
        .onFocusChanged { focused = it.hasFocus }
        .background(if (focused) panel else Color.Transparent, CircleShape)
        .border(2.dp, if (focused) focusColor else Color.Transparent, CircleShape)) {
        SolidIcon(SolidIcon.ArrowUp, Modifier.size(28.dp), lime)
    }
}

@Composable internal fun AppUpdateDialog(state: AppUpdateState, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val release = state.release ?: return
    if (!state.visible) return
    AppDialog(onDismissRequest = onDismiss, title = { Text("Futbolio ${release.tag} is available") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Download the latest release from GitHub. Android will ask you to confirm installation; your settings will be kept.")
            if (state.downloading) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = lavender)
                Text("Downloading… ${state.progress}%", style = MaterialTheme.typography.bodySmall)
            } else if (state.ready != null) Text("Download verified. Ready to install.", color = lavender)
            else Text("Download size: %.1f MB".format(release.bytes / 1048576.0), style = MaterialTheme.typography.bodySmall)
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TvAction(if (state.ready != null) "Install update" else "Download and install", enabled = !state.downloading,
            icon = SolidIcon.Install, onClick = onUpdate)
    }, dismissButton = { TvAction(if (state.downloading) "Cancel" else "Later", onClick = onDismiss) })
}
