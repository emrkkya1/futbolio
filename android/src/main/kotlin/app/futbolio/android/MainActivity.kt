package app.futbolio.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.futbolio.android.bootstrap.FutbolioApplication
import app.futbolio.android.presentation.*
import app.futbolio.android.ui.TvScreen
import app.futbolio.android.ui.tvTheme
import app.futbolio.android.ui.futbolioTypography
import app.futbolio.android.ui.AppUpdateDialog
import app.futbolio.android.updates.AppUpdateViewModel
import app.futbolio.application.ports.readLimited
import kotlinx.coroutines.*

/** Activity-owned platform effects only: documents, clipboard, intents and permissions. */
class MainActivity : ComponentActivity() {
    private val updates: AppUpdateViewModel by lazy { ViewModelProvider(this)[AppUpdateViewModel::class.java] }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val allowUpdates = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) launchUpdateInstaller()
        else updates.installError("Allow Futbolio to install updates, then tap Install update again.")
    }
    private val model: FutbolioViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FutbolioViewModel((application as FutbolioApplication).container) as T
        })[FutbolioViewModel::class.java]
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            try { contentResolver.openOutputStream(uri)?.use { it.write(model.exportText().toByteArray()) } ?: error("No output"); model.notify("Providers exported.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.notify("Could not export providers.") }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { readLimited(it, 128 * 1024).toString(Charsets.UTF_8) } ?: error("No input")
                val result = model.importText(text)
                if (!result.saved) model.notify(result.error)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.notify("Could not import providers. Choose a valid Futbolio or legacy configuration.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.effects.collect(::effect) } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            updates.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
        } }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val update by updates.state.collectAsStateWithLifecycle()
            LaunchedEffect(update.ready) { if (updates.takeInstallRequest()) installUpdate() }
            MaterialTheme(colorScheme = tvTheme, typography = futbolioTypography) {
                Surface(Modifier.fillMaxSize()) {
                    TvScreen(state, model, onCheckUpdates = { updates.checkForUpdates(manual = true) },
                        updateAvailable = update.release != null, onOpenUpdate = updates::show)
                }
                AppUpdateDialog(update, onUpdate = {
                    if (update.ready == null) updates.download() else installUpdate()
                }, onDismiss = updates::dismiss)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        updates.checkForUpdates()
    }
    private fun installUpdate() {
        if (updates.state.value.ready == null) return
        if (packageManager.canRequestPackageInstalls()) launchUpdateInstaller()
        else try {
            allowUpdates.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            updates.installError("This device has no update-permission screen. Allow installs from Futbolio in Android settings.")
        } catch (_: SecurityException) { updates.installError("Android blocked permission to install updates.") }
    }
    private fun launchUpdateInstaller() {
        val file = updates.state.value.ready ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            updateInstaller.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) { updates.installError("No Android package installer is available on this device.") }
        catch (_: Exception) { updates.installError("Could not open the Android installer. Please try again.") }
    }
    private fun effect(effect: AndroidEffect) {
        when (effect) {
            is AndroidEffect.Copy -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Futbolio", effect.text)); model.notify("Copied.") }
            AndroidEffect.Install -> install()
            AndroidEffect.Import -> try { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                catch (_: ActivityNotFoundException) { model.notify("No document picker is installed on this TV. Edit providers manually or install a compatible file manager.") }
            AndroidEffect.Export -> try { exportDocument.launch("futbolio-providers.json") }
                catch (_: ActivityNotFoundException) { model.notify("No document picker is installed on this TV. Install a compatible file manager to export settings.") }
            AndroidEffect.Notifications -> if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
    private fun install() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppSettings.MANIFEST.replace("http://", "stremio://")))) }
        catch (_: ActivityNotFoundException) {
            val launch = packageManager.getLaunchIntentForPackage("com.stremio.one")
            if (launch != null) { startActivity(launch); model.notify("Stremio opened. If no add-on was installed, use the installation help.") }
            else model.notify("Install Stremio for Android TV, then return to Futbolio.")
        }
    }
}
