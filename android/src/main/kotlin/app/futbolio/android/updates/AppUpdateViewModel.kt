package app.futbolio.android.updates

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest

internal data class AppUpdateState(
    val release: GitHubRelease? = null,
    val visible: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: File? = null,
    val error: String = "",
)

/** An Activity-scoped model: rotation preserves downloads; the relay remains independent. */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = ReleaseClient()
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastAttempt: Long? = null
    private var notifiedTag: String? = null
    private var installRequested = false
    private val app get() = getApplication<Application>()

    fun checkForUpdates(manual: Boolean = false) {
        if (checkJob?.isActive == true || state.value.downloading) return
        if (!manual && lastAttempt?.let { SystemClock.elapsedRealtime() - it < 30 * 60_000 } == true) return
        lastAttempt = SystemClock.elapsedRealtime()
        checkJob = viewModelScope.launch {
            if (manual) notices.send("Checking for updates…")
            try {
                val release = withContext(Dispatchers.IO) {
                    val version = installed().versionName ?: return@withContext null
                    client.latest()?.let { ReleasePolicy.select(it, version) }
                }
                if (release != null) {
                    if (manual || notifiedTag != release.tag) {
                        notifiedTag = release.tag
                        installRequested = false
                        mutableState.value = AppUpdateState(release = release, visible = true)
                        notices.send("Futbolio ${release.tag} is available")
                    }
                } else if (manual) notices.send("No compatible newer release is available.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (manual) notices.send("Could not check GitHub releases. Check your connection and try again.") }
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        mutableState.update { it.copy(visible = false, downloading = false) }
    }

    fun show() {
        if (state.value.release != null) mutableState.update { it.copy(visible = true) }
    }

    fun download() {
        val release = state.value.release ?: return
        if (downloadJob?.isCompleted == false || state.value.ready != null) return
        mutableState.update { it.copy(downloading = true, progress = 0, error = "") }
        downloadJob = viewModelScope.launch {
            val directory = File(app.cacheDir, "app-updates")
            val partial = File(directory, "update-download.apk")
            val apk = File(directory, "update.apk")
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the update download." }
                    client.download(release, partial) { percent -> mutableState.update { it.copy(progress = percent) } }
                    validateApk(partial, release)
                    check(partial.renameTo(apk)) { "Could not save the downloaded update." }
                }
                mutableState.update { it.copy(downloading = false, ready = apk) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(downloading = false, error = failure.message?.take(200) ?: "Could not download the update. Please retry.") }
            } finally { withContext(NonCancellable + Dispatchers.IO) { partial.delete() } }
        }
    }

    fun installError(message: String) { mutableState.update { it.copy(error = message) } }

    fun takeInstallRequest(): Boolean {
        if (state.value.ready == null || installRequested) return false
        installRequested = true
        return true
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = app.packageManager.getPackageInfo(app.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: GitHubRelease) {
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: error("The downloaded file is not a valid Android APK.")
        val current = installed()
        check(archive.packageName == app.packageName) { "The downloaded APK is not Futbolio." }
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(code(archive) == release.version.code && code(archive) > code(current) && ReleaseVersion.parse(archive.versionName.orEmpty()) == release.version) {
            "The downloaded APK version did not match the release."
        }
        fun signatures(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val trusted = signatures(current)
        check(trusted.isNotEmpty() && trusted == signatures(archive)) {
            "This APK uses a different signing key from your installed build. It cannot safely update this installation."
        }
    }
}
