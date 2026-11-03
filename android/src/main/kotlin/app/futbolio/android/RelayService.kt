package app.futbolio.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import app.futbolio.android.bootstrap.FutbolioApplication
import app.futbolio.application.api.MediaActivity
import java.util.concurrent.atomic.AtomicInteger

/** Android lifecycle/notification/wake adapter. Engine ownership is in the process host. */
class RelayService : Service() {
    companion object { const val STOP = "app.futbolio.STOP" }
    private val container get() = (application as FutbolioApplication).container
    private val posters = AndroidPosters()
    private val media = AtomicInteger()
    private var owner = 0L
    private var destroyed = false
    private lateinit var wake: PowerManager.WakeLock
    private var renewed = 0L

    override fun onCreate() {
        super.onCreate()
        owner = container.host.claimOwner()
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Futbolio:streaming").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { container.stopFromService(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("relay", "Futbolio relay", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 2, Intent(this, RelayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "relay").setSmallIcon(R.drawable.ic_futbolio).setContentTitle("Futbolio is running")
                .setContentText("Stremio add-on · this device only").setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(10, notification)
        } catch (_: Exception) {
            container.host.reportFailure("Android did not allow the service. Start it while Futbolio is visible.")
            container.stopFromService()
            return START_NOT_STICKY
        }
        container.host.start(owner, posters, object : MediaActivity {
            override fun started() { media.incrementAndGet(); renewWake() }
            override fun progress() = renewWake()
            override fun finished() { media.decrementAndGet(); releaseIfIdle() }
        }) { Handler(mainLooper).post { stopSelf() } }
        return START_STICKY
    }

    @Synchronized private fun renewWake() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (!wake.isHeld || now - renewed > 30_000) { wake.acquire(90_000); renewed = now }
    }
    @Synchronized private fun releaseIfIdle() { if (media.get() <= 0 && wake.isHeld) wake.release() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_RUNNING_LOW) { posters.trim(); container.host.trim() } }
    override fun onDestroy() {
        synchronized(this) { destroyed = true; if (wake.isHeld) wake.release() }
        container.host.releaseOwner(owner) // Cleanup is bounded and owned by the application's scope, not the UI thread.
        posters.trim()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
