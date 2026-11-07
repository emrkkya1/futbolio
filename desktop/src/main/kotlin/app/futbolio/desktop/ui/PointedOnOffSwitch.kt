package app.futbolio.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun PointedOnOffSwitch(value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var focusedOn by remember { mutableStateOf(false) }
    var focusedOff by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(52.dp).testTag("times-and-scores")
        .clip(pointedButton).background(edge)
        .border(2.dp, if (focusedOn || focusedOff) lavender else Color.Transparent, pointedButton)) {
        val direction = if (value) 1f else -1f
        Box(Modifier.matchParentSize().graphicsLayer(scaleX = direction).background(lime, pointedOnSegment))
        Box(Modifier.matchParentSize().graphicsLayer(scaleX = direction).background(Color(0xff353535), pointedOffSegment))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { focusedOn = it.hasFocus }
                .semantics { contentDescription = "Times and Scores on" }
                .selectable(selected = value, enabled = enabled, role = Role.RadioButton, onClick = { if (!value) onChange(true) }),
                contentAlignment = Alignment.Center) {
                Text("On", style = MaterialTheme.typography.labelLarge, color = if (value) Color.White else quiet)
            }
            Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { focusedOff = it.hasFocus }
                .semantics { contentDescription = "Times and Scores off" }
                .selectable(selected = !value, enabled = enabled, role = Role.RadioButton, onClick = { if (value) onChange(false) }),
                contentAlignment = Alignment.Center) {
                Text("Off", style = MaterialTheme.typography.labelLarge, color = if (!value) Color.White else quiet)
            }
        }
    }
}
