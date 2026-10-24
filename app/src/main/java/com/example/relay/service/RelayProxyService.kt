package com.example.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.relay.data.ProxyConfig
import com.example.relay.proxy.ProxyServerManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RelayProxyService : Service() {

    private val serviceScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled coroutine exception", e)
        }
    )

    companion object {
        const val ACTION_START = "com.example.relay.START"
        const val ACTION_STOP = "com.example.relay.STOP"

        const val EXTRA_WORKER_URL = "extra_worker_url"
        const val EXTRA_SECRET = "extra_secret"
        const val EXTRA_CLEAN_IP = "extra_clean_ip"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"
        const val EXTRA_HTTP_PORT = "extra_http_port"
        const val EXTRA_ALLOW_LAN = "extra_allow_lan"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "v3_relay_proxy_channel"
        private const val TAG = "RelayProxyService"

        fun startService(
            context: Context,
            workerUrl: String,
            secret: String,
            cleanIp: String,
            socksPort: Int,
            httpPort: Int,
            allowLan: Boolean = false
        ) {
            val intent = Intent(context, RelayProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORKER_URL, workerUrl)
                putExtra(EXTRA_SECRET, secret)
                putExtra(EXTRA_CLEAN_IP, cleanIp)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_HTTP_PORT, httpPort)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RelayProxyService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, RelayProxyService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop RelayProxyService gracefully", e)
                try {
                    context.stopService(Intent(context, RelayProxyService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val workerUrl = intent.getStringExtra(EXTRA_WORKER_URL) ?: ""
                val secret = intent.getStringExtra(EXTRA_SECRET) ?: ""
                val cleanIp = intent.getStringExtra(EXTRA_CLEAN_IP) ?: ""
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080)
                val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 8080)
                val allowLan = intent.getBooleanExtra(EXTRA_ALLOW_LAN, false)

                safeStartForeground(buildNotification(getString(R.string.notif_proxy_title), getString(R.string.notif_connecting)))

                val config = ProxyConfig(
                    workerUrl = workerUrl,
                    secret = secret,
                    cleanIp = cleanIp,
                    socksPort = socksPort,
                    httpPort = httpPort,
                    allowLan = allowLan
                )

                ProxyServerManager.start(config)

                updateNotification(
                    title = getString(R.string.notif_proxy_active),
                    content = if (httpPort <= 0) {
                        getString(R.string.notif_ports_socks_only, socksPort)
                    } else {
                        getString(R.string.notif_ports, socksPort, httpPort)
                    }
                )

                // Observe isRunning to stop self if stopped from manager
                serviceScope.launch {
                    ProxyServerManager.isRunning.collect { running ->
                        if (!running) {
                            stopSelf()
                        }
                    }
                }
            }

            ACTION_STOP -> {
                try {
                    ProxyServerManager.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop proxy cleanly", e)
                } finally {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {}
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun safeStartForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeStartForeground failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_proxy),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_proxy_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RelayProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_disconnect), stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(title, content))
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { serviceScope.cancel() } catch (_: Throwable) {}
        try { ProxyServerManager.stop() } catch (_: Throwable) {}
    }
}
