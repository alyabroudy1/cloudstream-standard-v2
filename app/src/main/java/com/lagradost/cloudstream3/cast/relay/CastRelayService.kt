package com.lagradost.cloudstream3.cast.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lagradost.api.Log
import com.lagradost.cloudstream3.R

/**
 * Foreground service that keeps the cast relay server alive when the app
 * is backgrounded or the screen is off.
 *
 * Without this service, Android may kill the app process or suspend
 * network I/O, causing the DLNA cast to stop mid-stream.
 *
 * Lifecycle:
 * - Started by [CastSessionManager] when a relay-needing session connects
 * - Stopped by [CastSessionManager] when the session disconnects
 *
 * Holds a partial WakeLock and a WiFi lock to keep the CPU and WiFi
 * radio active during streaming.
 */
class CastRelayService : Service() {

    companion object {
        private const val TAG = "CastRelayService"
        private const val CHANNEL_ID = "cast_relay_channel"
        private const val NOTIFICATION_ID = 9281
        private const val STOP_ACTION = "com.lagradost.cloudstream3.cast.STOP_CASTING"

        fun start(context: Context) {
            val intent = Intent(context, CastRelayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CastRelayService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CastRelayService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CastRelayService: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            Log.d(TAG, "Stop action received")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Starting foreground cast relay service")
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        Log.d(TAG, "Cast relay service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Casting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while casting video to a TV"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CastRelayService::class.java).apply {
            action = STOP_ACTION
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.casting))
            .setContentText(getString(R.string.casting_notification_text))
            .setSmallIcon(R.drawable.ic_baseline_cast_connected_24)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.baseline_stop_24,
                getString(R.string.stop),
                stopPendingIntent
            )
            .build()
    }

    // ── Wake / WiFi Locks ────────────────────────────────────────────

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CloudStream::CastRelay"
            ).apply {
                acquire(4 * 60 * 60 * 1000L) // 4 hours max
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "CloudStream::CastRelay"
            ).apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }

        Log.d(TAG, "Acquired WakeLock and WifiLock")
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }
        Log.d(TAG, "Released WakeLock and WifiLock")
    }
}
