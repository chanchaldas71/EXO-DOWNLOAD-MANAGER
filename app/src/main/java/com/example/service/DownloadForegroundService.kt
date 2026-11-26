package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PlexoApplication
import com.example.R
import com.example.engine.download.DownloadLiveMetrics
import com.example.engine.download.EngineAlertEvent
import com.example.ui.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground download service (`foregroundServiceType="dataSync"`) that keeps multi-network
 * downloads alive in the background with WifiLock + WakeLock and rich notification actions.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notificationManager: NotificationManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var isInForeground = false

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val app = application as PlexoApplication
        serviceScope.launch {
            app.downloadEngine.liveMetrics.collect { metricsMap ->
                updateOngoingNotification(metricsMap)
            }
        }
        serviceScope.launch {
            app.downloadEngine.alerts.collect { alert ->
                handleAlertNotification(alert)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PlexoApplication

        // Satisfy Android's startForegroundService() contract synchronously on EVERY entry path
        // before any branch can stop the service.
        if (!isInForeground) {
            startForegroundSafe(
                buildOngoingNotification(app.downloadEngine.liveMetrics.value, speedMbps = false)
            )
        }

        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                serviceScope.launch {
                    app.downloadEngine.pauseAll()
                    releaseLocksAndStop()
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_ALL -> {
                acquireLocks()
                serviceScope.launch {
                    app.downloadEngine.resumeAll()
                }
                return START_STICKY
            }
            ACTION_CANCEL_ALL -> {
                serviceScope.launch {
                    app.downloadEngine.pauseAll()
                    releaseLocksAndStop()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_IF_IDLE -> {
                if (!app.downloadEngine.hasActiveDownloads()) {
                    releaseLocksAndStop()
                }
                return START_NOT_STICKY
            }
            else -> {
                acquireLocks()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        isInForeground = false
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_ONGOING, notification)
        }
        isInForeground = true
    }

    private suspend fun updateOngoingNotification(metricsMap: Map<Long, DownloadLiveMetrics>) {
        // Wait until onStartCommand() has promoted the service to foreground before stopping or notifying
        if (!isInForeground) return

        val app = application as PlexoApplication
        if (metricsMap.isEmpty()) {
            if (!app.downloadEngine.hasActiveDownloads()) {
                releaseLocksAndStop()
            }
            return
        }
        val settings = app.settingsRepository.getCurrentSettings()
        val notification = buildOngoingNotification(metricsMap, settings.speedUnitMbps)
        notificationManager.notify(NOTIFICATION_ID_ONGOING, notification)
    }

    private fun buildOngoingNotification(
        metricsMap: Map<Long, DownloadLiveMetrics>,
        speedMbps: Boolean
    ): Notification {
        val totalSpeed = metricsMap.values.sumOf { it.totalSpeedBytesPerSec }
        val activeDownloads = metricsMap.size
        val totalStreams = metricsMap.values.sumOf { it.activeStreamCount }
        val activeNets = metricsMap.values
            .flatMap { it.perNetworkStats }
            .filter { it.activeStreams > 0 }
            .map { it.networkId }
            .distinct()
            .size

        val downloadedSum = metricsMap.values.sumOf { it.downloadedBytes }
        val totalSum = metricsMap.values.filter { it.totalBytes > 0L }.sumOf { it.totalBytes }
        val progressPct = if (totalSum > 0L) ((downloadedSum * 100L) / totalSum).toInt().coerceIn(0, 100) else -1

        val speedText = Formatters.formatSpeed(totalSpeed, speedMbps)
        val title = if (activeDownloads == 0) {
            "Plexo bonding engine active"
        } else {
            "$activeDownloads downloading · $speedText"
        }
        val subtitle = if (activeDownloads == 0) {
            "Preparing multi-network streams…"
        } else {
            "$activeNets network(s) bonded · $totalStreams streams"
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_RESUME_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progressPct.coerceAtLeast(0), progressPct < 0)
            .addAction(0, getString(R.string.action_pause_all), pauseIntent)
            .addAction(0, getString(R.string.action_resume_all), resumeIntent)
            .addAction(0, getString(R.string.action_cancel), cancelIntent)
            .build()
    }

    private fun handleAlertNotification(alert: EngineAlertEvent) {
        val (title, body, idOffset) = when (alert) {
            is EngineAlertEvent.Completed -> Triple(
                "Download complete",
                alert.fileName,
                alert.downloadId.toInt() + 1000
            )
            is EngineAlertEvent.Failed -> Triple(
                "Download failed: ${alert.fileName}",
                alert.reason,
                alert.downloadId.toInt() + 2000
            )
            is EngineAlertEvent.ExpiredLink -> Triple(
                "Link expired: ${alert.fileName}",
                "Open Plexo and paste a refreshed URL to resume without losing progress.",
                alert.downloadId.toInt() + 3000
            )
            is EngineAlertEvent.AdapterDropped -> Triple(
                "Network adapter lost",
                "Reassigned chunks from ${alert.networkName} to remaining active networks.",
                4001
            )
            is EngineAlertEvent.ResumeMismatch -> return
        }

        val openIntent = PendingIntent.getActivity(
            this,
            idOffset,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(idOffset, notif)
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Plexo::DownloadWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 1h safety timeout
                }
            }
        } catch (_: Exception) {
        }

        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "Plexo::DownloadWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun releaseLocksAndStop() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE,
                getString(R.string.notification_channel_active),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_active_desc)
                setShowBadge(false)
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_alerts_desc)
            }
            notificationManager.createNotificationChannel(activeChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_ACTIVE = "plexo_active_downloads"
        const val CHANNEL_ALERTS = "plexo_download_alerts"
        const val NOTIFICATION_ID_ONGOING = 101

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE_ALL = "com.example.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.example.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.example.action.CANCEL_ALL"
        const val ACTION_STOP_IF_IDLE = "com.example.action.STOP_IF_IDLE"

        @Volatile
        private var isServiceRunning = false

        fun ensureStarted(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Background start restriction on rare OEM cases; engine continues in-process
            }
        }

        fun stopIfIdle(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_STOP_IF_IDLE)
                context.startService(intent)
            }
        }
    }
}
