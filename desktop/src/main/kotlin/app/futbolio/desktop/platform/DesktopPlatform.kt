package app.futbolio.desktop.platform

import app.futbolio.desktop.lanAddresses
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DesktopPlatform {
    fun addresses(): List<String>
    fun copy(text: String)
    fun browse(url: String)
    suspend fun chooseFile(save: Boolean): Path?
}

object SystemDesktopPlatform : DesktopPlatform {
    override fun addresses() = lanAddresses()
    override fun copy(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    override fun browse(url: String) { Desktop.getDesktop().browse(URI(url)) }
    override suspend fun chooseFile(save: Boolean): Path? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            if (!continuation.isActive) return@invokeLater
            val dialog = FileDialog(null as Frame?, if (save) "Export Futbolio providers" else "Import providers", if (save) FileDialog.SAVE else FileDialog.LOAD)
            continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
            try {
                dialog.file = if (save) "futbolio-providers.json" else "*.json"
                dialog.isVisible = true
                val selected = dialog.file?.let { Path.of(dialog.directory, it) }
                if (continuation.isActive) continuation.resume(selected)
            } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            finally { dialog.dispose() }
        }
    }
}
