package app.futbolio.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.futbolio.application.api.*
import app.futbolio.application.presentation.*
import app.futbolio.domain.provider.SourceConfig

internal val lime = Color(0xffb4ff62)
internal val emerald = Color(0xff1ee69b)
internal val quiet = Color(0xff8b9c92)
internal val panel = Color(0xff101713)
internal val edge = Color(0xff24372c)

@Composable internal fun DashboardBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xff040705))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color(0xff167d40).copy(alpha = .34f), Color.Transparent),
                center = Offset(size.width * .82f, -size.height * .16f), radius = size.width * .82f))
        }
        content()
    }
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
        drawPath(path, Brush.linearGradient(listOf(lime, emerald)))
    }
}

@Composable internal fun RelayHero(runtime: RuntimeSnapshot, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val running = runtime.running
    val busy = runtime.phase in listOf(RuntimePhase.STARTING, RuntimePhase.STOPPING)
    val activation by animateFloatAsState(if (running) 1f else 0f, tween(650), label = "relay-activation")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (!compact) Column(Modifier.weight(1f).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YOUR MATCH. YOUR SCREEN.", color = emerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(if (running) "Match day.\nMade simple." else "Ready for\nkick-off.", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-1).sp)
                Text("Your football. Straight to Stremio.", color = quiet, fontSize = 14.sp)
            }
            Column(if (compact) Modifier.fillMaxWidth() else Modifier.width(300.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    FutbolioMark(Modifier.size(46.dp))
                    Text("Futbolio", color = Color.White, fontWeight = FontWeight.Black, fontSize = 43.sp, letterSpacing = (-2).sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Canvas(Modifier.size(9.dp)) { drawCircle(if (running) emerald else quiet)
                        if (activation in .01f.. .99f) drawCircle(emerald.copy(alpha = 1f - activation), radius = size.width * (1f + activation), style = Stroke(2f)) }
                    Text(when (runtime.phase) { RuntimePhase.RUNNING -> "Relay online"; RuntimePhase.STARTING -> "Starting relay…"; RuntimePhase.STOPPING -> "Stopping relay…"; else -> "Relay offline" },
                        color = if (running) emerald else quiet, fontSize = 14.sp)
                    Text("· this device", color = quiet, fontSize = 12.sp)
                }
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(17.dp)
                Button(onClick = onToggle, enabled = !busy, modifier = modifier.testTag("relay-toggle").width(252.dp).height(56.dp)
                    .onFocusChanged { focused = it.hasFocus }.background(Brush.horizontalGradient(listOf(lime, emerald)), shape)
                    .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape),
                    shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xff05160c), disabledContainerColor = panel, disabledContentColor = quiet)) {
                    if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = emerald)
                    else Canvas(Modifier.size(20.dp)) {
                        val ink = Color(0xff05160c)
                        if (running) {
                            drawRect(ink, Offset(size.width * .2f, size.height * .15f), androidx.compose.ui.geometry.Size(size.width * .2f, size.height * .7f))
                            drawRect(ink, Offset(size.width * .6f, size.height * .15f), androidx.compose.ui.geometry.Size(size.width * .2f, size.height * .7f))
                        } else drawPath(Path().apply { moveTo(size.width * .25f, size.height * .1f); lineTo(size.width * .9f, size.height * .5f); lineTo(size.width * .25f, size.height * .9f); close() }, ink)
                    }
                    Spacer(Modifier.width(11.dp))
                    Text(if (busy) runtime.phase.label + "…" else if (running) "Stop relay" else "Start relay", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                if (running) Text("${runtime.diagnostics.activeMedia} active · %.1f MB relayed".format(runtime.diagnostics.mediaBytes / 1048576.0), color = quiet, fontSize = 11.sp)
            }
        }
    }
}

@Composable internal fun ProviderTile(source: SourceConfig, runtime: RuntimeSnapshot, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val health = providerCardState(source, runtime)
    var focused by remember { mutableStateOf(false) }
    val active = runtime.testBatch.activeProviderId == source.id
    Surface(color = if (focused) Color(0xff19291e) else panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused || active) emerald else edge),
        modifier = modifier.testTag("provider-${source.id}").onFocusChanged { focused = it.hasFocus }.clickable(onClick = onClick).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(source.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HealthLine("Connected", health.connected)
            HealthLine("Catalog", health.catalog)
            HealthLine("Stream Fetching", health.fetching)
            Text(if (active) "TESTING NOW" else "VIEW DETAILS  ›", color = if (active) emerald else quiet, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .7.sp)
        }
    }
}

@Composable private fun HealthLine(label: String, value: HealthValue) {
    val color = when (value.tone) { HealthTone.GOOD -> emerald; HealthTone.WARNING -> Color(0xffe9c875); HealthTone.BAD -> Color(0xffff8e91); HealthTone.CHECKING -> lime; else -> quiet }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = quiet, fontSize = 11.sp, lineHeight = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(5.dp)) { drawCircle(color) }
            Text(value.text, color = color, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
