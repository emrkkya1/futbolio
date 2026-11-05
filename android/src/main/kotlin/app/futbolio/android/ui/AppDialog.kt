package app.futbolio.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val LocalDialogActionModifier = staticCompositionLocalOf<Modifier> { Modifier }
internal val LocalDialogFooter = staticCompositionLocalOf { false }

/** Full-width body actions and equal-width footer actions; Close keeps its existing slot. */
@Composable internal fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    containerColor: Color = panel,
) {
    AlertDialog(onDismissRequest = onDismissRequest, containerColor = containerColor, title = title,
        text = { CompositionLocalProvider(LocalDialogActionModifier provides Modifier.fillMaxWidth()) { text() } },
        confirmButton = {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalDialogActionModifier provides Modifier.fillMaxSize(), LocalDialogFooter provides true) {
                    if (dismissButton != null) Box(Modifier.weight(1f).fillMaxHeight()) { dismissButton() }
                    else Spacer(Modifier.weight(1f))
                    if (confirmButton != null) Box(Modifier.weight(1f).fillMaxHeight()) { confirmButton() }
                    else Spacer(Modifier.weight(1f))
                }
            }
        })
}
