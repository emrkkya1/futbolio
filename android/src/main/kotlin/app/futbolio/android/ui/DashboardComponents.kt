package app.futbolio.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.futbolio.application.api.*
import app.futbolio.application.presentation.*
import app.futbolio.domain.provider.SourceConfig

internal val lime = Color(0xff4fa14c)
internal val emerald = lime
internal val lavender = Color(0xffa5a0cb)
internal val quiet = Color(0xffb5b5b5)
internal val panel = Color(0xff262626)
internal val edge = Color(0xff3b3b3b)
internal val backdrop = Color(0xff1c1c1c)

@Composable internal fun DashboardBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(backdrop), content = content)
}

@Composable internal fun FutbolioMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(s * .5f, 0f); lineTo(s, s * .5f); lineTo(s * .5f, s); lineTo(0f, s * .5f); close()
            moveTo(s * 13 / 42, s * 10 / 42); lineTo(s * 29 / 42, s * 10 / 42)
            lineTo(s * 29 / 42, s * 16 / 42); lineTo(s * 19 / 42, s * 16 / 42)
            lineTo(s * 19 / 42, s * 20 / 42); lineTo(s * 27 / 42, s * 20 / 42)
            lineTo(s * 27 / 42, s * 26 / 42); lineTo(s * 19 / 42, s * 26 / 42)
            lineTo(s * 19 / 42, s * 32 / 42); lineTo(s * 13 / 42, s * 32 / 42); close()
        }
        drawPath(path, lime)
    }
}

@Composable internal fun BrandHeader(compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(if (compact) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            FutbolioMark(Modifier.size(if (compact) 38.dp else 62.dp))
            Text("Futbolio", fontFamily = kodchasan, fontSize = if (compact) 25.sp else 36.sp,
                lineHeight = if (compact) 32.sp else 44.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), color = lime)
        }
        if (!compact) Text("Relay football matches to Stremio", Modifier.padding(start = 80.dp),
            style = MaterialTheme.typography.bodyMedium, color = quiet)
    }
}

@Composable internal fun RelayHero(runtime: RuntimeSnapshot, modifier: Modifier = Modifier, centered: Boolean = false, onToggle: () -> Unit) {
    val busy = runtime.phase in listOf(RuntimePhase.STARTING, RuntimePhase.STOPPING)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "relay-focus")
    val label = if (busy) "${runtime.phase.label}…" else if (runtime.running) "Stop Futbolio" else "Start Futbolio"
    val relayButton: @Composable () -> Unit = {
        Button(onClick = onToggle, enabled = !busy,
            modifier = modifier.testTag("relay-toggle").size(if (centered) 116.dp else 158.dp).scale(scale)
                .semantics { contentDescription = label }
                .onFocusChanged { focused = it.hasFocus }
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, relayDiamond),
            shape = relayDiamond, contentPadding = PaddingValues(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = Color.White,
                disabledContainerColor = panel, disabledContentColor = quiet)) {
            if (busy) CircularProgressIndicator(Modifier.size(if (centered) 36.dp else 48.dp), color = lavender, strokeWidth = 3.dp)
            else SolidIcon(if (runtime.running) SolidIcon.Stop else SolidIcon.Play, Modifier.size(if (centered) 54.dp else 72.dp))
        }
    }
    Column(if (centered) Modifier.fillMaxWidth() else Modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (centered) 8.dp else 12.dp)) {
        if (centered) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            relayButton()
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            relayButton()
            Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Canvas(Modifier.size(8.dp)) { drawCircle(if (runtime.running) lime else quiet) }
            Text(when (runtime.phase) {
                RuntimePhase.RUNNING -> "Relay is live"
                RuntimePhase.STARTING -> "Starting relay…"
                RuntimePhase.STOPPING -> "Stopping relay…"
                else -> "Relay is stopped"
            }, style = if (centered) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, color = Color.White)
        }
        // Provider totals are listings, not deduplicated football matches or live/upcoming counts.
        val enabled = runtime.providers.filter { it.enabled }
        Text(when {
            !runtime.running -> "Start Futbolio to load your sources"
            enabled.any { it.loading } -> "Loading source catalogs…"
            enabled.isEmpty() -> "No stream sources enabled"
            else -> "${enabled.sumOf { it.count }} listings across ${enabled.size} sources"
        }, color = quiet, style = if (centered) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun ProviderTile(source: SourceConfig, runtime: RuntimeSnapshot, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val health = providerCardState(source, runtime)
    val focusColor = focusOutlineColor()
    var focused by remember { mutableStateOf(false) }
    val active = runtime.testBatch.activeProviderId == source.id
    val shape = RoundedCornerShape(8.dp)
    Surface(color = if (focused) panel else Color.Transparent, shape = shape,
        border = BorderStroke(2.dp, if (focused) focusColor else if (active) lavender else Color.Transparent),
        modifier = modifier.testTag("provider-${source.id}").onFocusChanged { focused = it.hasFocus }.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                SolidIcon(SolidIcon.Chevron, Modifier.size(20.dp), quiet)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HealthLine("Connection", health.connected, Modifier.weight(1f))
                HealthLine("Catalog", health.catalog, Modifier.weight(1f))
                HealthLine("Stream fetch", health.fetching, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun HealthLine(label: String, value: HealthValue, modifier: Modifier = Modifier) {
    val color = when (value.tone) {
        HealthTone.GOOD -> Color(0xff80c77d)
        HealthTone.WARNING -> Color(0xffe9c875)
        HealthTone.BAD -> Color(0xffff9999)
        HealthTone.CHECKING -> lavender
        else -> quiet
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = quiet, fontSize = 11.sp, lineHeight = 15.sp)
        Text(value.text, color = color, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
