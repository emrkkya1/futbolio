package app.futbolio.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path as GraphicPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.futbolio.desktop.cli.runCommandLine
import app.futbolio.desktop.ui.DesktopScreen
import app.futbolio.desktop.ui.theme
import app.futbolio.desktop.ui.futbolioTypography
import kotlinx.coroutines.*
import java.awt.SystemTray
import java.nio.file.Path

fun main(args: Array<String>) {
    if (runCommandLine(args)) return
    val dataIndex = args.indexOf("--data-dir")
    val host = if (dataIndex >= 0) DesktopHost(Path.of(args.getOrNull(dataIndex + 1) ?: error("Missing data directory"))) else DesktopHost()
    if ("--headless" in args) {
        Runtime.getRuntime().addShutdownHook(Thread { host.close() })
        runBlocking { host.start(); check(host.runtime.value.running) { host.error.value }; println("Futbolio: ${host.settings.value.host().manifest}"); awaitCancellation() }
        return
    }
    application {
        var visible by remember { mutableStateOf(true) }
        val tray = remember { SystemTray.isSupported() }
        val quit = { host.requestClose { exitApplication() } }
        val appIcon = remember { object : Painter() {
            override val intrinsicSize = Size(32f, 32f)
            override fun DrawScope.onDraw() {
                val s = size.minDimension
                drawPath(GraphicPath().apply {
                    fillType = PathFillType.EvenOdd
                    moveTo(s * .5f, 0f); lineTo(s, s * .5f); lineTo(s * .5f, s); lineTo(0f, s * .5f); close()
                    moveTo(s * 13 / 42, s * 10 / 42); lineTo(s * 29 / 42, s * 10 / 42); lineTo(s * 29 / 42, s * 16 / 42)
                    lineTo(s * 19 / 42, s * 16 / 42); lineTo(s * 19 / 42, s * 20 / 42); lineTo(s * 27 / 42, s * 20 / 42)
                    lineTo(s * 27 / 42, s * 26 / 42); lineTo(s * 19 / 42, s * 26 / 42); lineTo(s * 19 / 42, s * 32 / 42)
                    lineTo(s * 13 / 42, s * 32 / 42); close()
                }, Color(0xff3a8638))
            }
        } }
        if (tray) Tray(appIcon, tooltip = "Futbolio", onAction = { visible = true }) {
            Item("Open Futbolio", onClick = { visible = true })
            Item("Start", onClick = host::onStart)
            Item("Stop", onClick = host::onStop)
            Separator(); Item("Quit", onClick = quit)
        }
        Window(onCloseRequest = { if (tray) visible = false else quit() }, visible = visible, title = "Futbolio", icon = appIcon, state = rememberWindowState(width = 1280.dp, height = 800.dp)) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(640, 540) }
            val state by host.ui.collectAsState()
            MaterialTheme(colorScheme = theme, typography = futbolioTypography) { Surface(Modifier.fillMaxSize()) { DesktopScreen(state, host) } }
        }
        DisposableEffect(Unit) { onDispose { host.requestClose() } }
    }
}
