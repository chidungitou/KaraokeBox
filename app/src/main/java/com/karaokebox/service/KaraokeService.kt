package com.karaokebox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.karaokebox.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Foreground service: giữ web server + bộ phát sống khi Activity bị đóng. */
class KaraokeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        ServiceLocator.startServer()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "karaokebox:server").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "karaokebox:wifi").apply { acquire() }
        } catch (_: Exception) {
        }
        ServiceLocator.appScope.launch(Dispatchers.IO) { ServiceLocator.scanner.scanAll() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    /** Vuốt app khỏi danh sách gần đây: dừng hát và tắt dịch vụ, tránh phát ngầm sau khi đã thoát. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try { ServiceLocator.controller.stopAll() } catch (_: Exception) {}
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceLocator.stopServer()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("karaoke", "Karaoke Box", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, "karaoke")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Karaoke Box đang chạy")
            .setContentText("Máy chủ điều khiển qua điện thoại đang hoạt động")
            .setOngoing(true)
            .build()
    }
}
