package com.example.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.relay.crypto.StreamCrypt
import com.example.relay.data.LogType
import com.example.relay.data.ProxyConfig
import com.example.relay.data.ProxyMode
import com.example.relay.proxy.ProxyServerManager
import com.example.relay.tunnel.TunTcpHandler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RelayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled coroutine exception", e)
        }
    )
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var tunTcpHandler: TunTcpHandler? = null

    companion object {
        const val ACTION_START = "com.example.relay.VPN_START"
        const val ACTION_STOP = "com.example.relay.VPN_STOP"

        const val EXTRA_WORKER_URL = "extra_worker_url"
        const val EXTRA_SECRET = "extra_secret"
        const val EXTRA_CLEAN_IP = "extra_clean_ip"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"
        const val EXTRA_HTTP_PORT = "extra_http_port"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_EXCLUDED_APPS = "extra_excluded_apps"
        const val EXTRA_ALLOW_LAN = "extra_allow_lan"

        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "v3_relay_vpn_channel"
        private const val TAG = "RelayVpnService"
        private const val DEFAULT_DNS = "1.1.1.1, 8.8.8.8"

        fun startService(
            context: Context,
            workerUrl: String,
            secret: String,
            cleanIp: String,
            socksPort: Int,
            httpPort: Int,
            dnsServers: String = DEFAULT_DNS,
            excludedApps: ArrayList<String> = ArrayList(),
            allowLan: Boolean = false
        ) {
            val intent = Intent(context, RelayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORKER_URL, workerUrl)
                putExtra(EXTRA_SECRET, secret)
                putExtra(EXTRA_CLEAN_IP, cleanIp)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_HTTP_PORT, httpPort)
                putExtra(EXTRA_DNS, dnsServers)
                putStringArrayListExtra(EXTRA_EXCLUDED_APPS, excludedApps)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RelayVpnService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, RelayVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop RelayVpnService gracefully", e)
                try {
                    context.stopService(Intent(context, RelayVpnService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        VpnProtector.activeVpn = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val workerUrl = intent.getStringExtra(EXTRA_WORKER_URL) ?: ""
                val secret = intent.getStringExtra(EXTRA_SECRET) ?: ""
                val cleanIp = intent.getStringExtra(EXTRA_CLEAN_IP) ?: ""
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080)
                val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 8080)
                val dnsServers = intent.getStringExtra(EXTRA_DNS) ?: DEFAULT_DNS
                val excludedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS) ?: ArrayList()
                val allowLan = intent.getBooleanExtra(EXTRA_ALLOW_LAN, false)

                safeStartForeground(buildNotification(getString(R.string.notif_vpn_connecting), socksPort, httpPort))

                val config = ProxyConfig(
                    workerUrl = workerUrl,
                    secret = secret,
                    cleanIp = cleanIp,
                    socksPort = socksPort,
                    httpPort = httpPort,
                    mode = ProxyMode.VPN_MODE,
                    dnsServers = dnsServers,
                    excludedApps = excludedApps.toSet(),
                    allowLan = allowLan
                )

                // 1. Start local proxy servers
                ProxyServerManager.start(config)

                // 2. Establish VPN Interface
                try {
                    val builder = Builder()
                        .setSession("Electro VPN")
                        .addAddress("10.0.0.2", 24)
                        .addRoute("0.0.0.0", 0)
                        .setMtu(1500)
                        .setBlocking(true)

                    val dnsList = dnsServers.split(',', ';', ' ', '\n', '،')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    for (server in (if (dnsList.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else dnsList).take(4)) {
                        try {
                            builder.addDnsServer(server)
                        } catch (_: Exception) {}
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && httpPort > 0) {
                        try {
                            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", httpPort))
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set HTTP proxy on VPN builder", e)
                        }
                    }

                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {}

                    var excludedCount = 0
                    for (pkg in excludedApps) {
                        if (pkg == packageName) continue
                        try {
                            builder.addDisallowedApplication(pkg)
                            excludedCount++
                        } catch (_: Exception) {}
                    }
                    if (excludedCount > 0) {
                        ProxyServerManager.addLog("اسپلیت تانلینگ: $excludedCount اپ خارج از VPN", LogType.INFO)
                    }

                    val pfd = builder.establish()
                    if (pfd == null) {
                        ProxyServerManager.addLog("VPN could not be established (permission or system conflict)", LogType.ERROR)
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    vpnInterface = pfd
                    isRunning.set(true)

                    ProxyServerManager.addLog("VPN interface established (Tunneling full device)", LogType.SUCCESS)

                    // Start full packet handler thread (DNS + raw TCP for Telegram MTProto)
                    startTunPacketHandlerThread(workerUrl, secret, cleanIp.ifBlank { null })

                    updateNotification(
                        title = getString(R.string.notif_vpn_active_title),
                        content = getString(R.string.notif_vpn_content, socksPort, httpPort),
                        socksPort = socksPort,
                        httpPort = httpPort
                    )
                } catch (e: Exception) {
                    ProxyServerManager.addLog("Failed to establish VPN: ${e.message}", LogType.ERROR)
                    cleanupVpn()
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Observe isRunning to stop self if stopped from UI
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
                    cleanupVpn()
                    ProxyServerManager.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop VPN cleanly", e)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    private fun startTunPacketHandlerThread(
        workerUrl: String,
        secret: String,
        cleanIp: String?
    ) {
        val pfd = vpnInterface ?: return
        val outStream = FileOutputStream(pfd.fileDescriptor)
        val inStream = FileInputStream(pfd.fileDescriptor)

        val tcpHandler = TunTcpHandler(workerUrl, secret, cleanIp, outStream)
        tunTcpHandler = tcpHandler

        val dnsExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

        thread(start = true, isDaemon = true, name = "TunPacketHandler") {
            val packet = ByteArray(32768)

            while (isRunning.get()) {
                try {
                    val length = inStream.read(packet)
                    if (length <= 0) break

                    // IPv4 Header verification
                    val versionAndIhl = packet[0].toInt() and 0xFF
                    val version = versionAndIhl ushr 4
                    if (version != 4) continue

                    val ihl = (versionAndIhl and 0x0F) * 4
                    val protocol = packet[9].toInt() and 0xFF

                    // UDP Packet (protocol 17) -> DNS
                    if (protocol == 17 && length >= ihl + 8) {
                        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                        if (dstPort == 53) {
                            val copy = packet.copyOf(length)
                            try {
                                dnsExecutor.execute {
                                    try {
                                        handleDnsQuery(copy, length, ihl, dstPort, outStream)
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    // TCP Packet (protocol 6) -> raw TCP / Telegram
                    else if (protocol == 6 && length >= ihl + 20) {
                        try {
                            tcpHandler.handleTcpPacket(packet, length)
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    if (!isRunning.get()) break
                }
            }
            try { dnsExecutor.shutdownNow() } catch (_: Throwable) {}
            try { tcpHandler.close() } catch (_: Throwable) {}
        }
    }

    private fun handleDnsQuery(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        dstPort: Int,
        outStream: FileOutputStream
    ) {
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val dnsPayloadLen = udpLen - 8
        if (dnsPayloadLen <= 0 || ihl + 8 + dnsPayloadLen > length) return

        val dnsQuery = ByteArray(dnsPayloadLen)
        System.arraycopy(packet, ihl + 8, dnsQuery, 0, dnsPayloadLen)

        val dnsServers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        var replyBuf: ByteArray? = null
        var replyDnsLen = 0

        for (dnsServer in dnsServers) {
            val dnsSocket = try { DatagramSocket() } catch (_: Exception) { null } ?: continue
            try {
                protect(dnsSocket)
                dnsSocket.soTimeout = 1500
                val outPacket = DatagramPacket(
                    dnsQuery,
                    dnsQuery.size,
                    InetAddress.getByName(dnsServer),
                    53
                )
                dnsSocket.send(outPacket)

                val buf = ByteArray(4096)
                val replyPacket = DatagramPacket(buf, buf.size)
                dnsSocket.receive(replyPacket)
                replyBuf = buf
                replyDnsLen = replyPacket.length
                break
            } catch (_: Exception) {
                // Try next DNS server
            } finally {
                try { dnsSocket.close() } catch (_: Exception) {}
            }
        }

        if (replyBuf == null || replyDnsLen <= 0) return

        val replyTotalLen = 20 + 8 + replyDnsLen
        val replyIp = ByteArray(replyTotalLen)

        // IPv4 header
        replyIp[0] = 0x45.toByte()
        replyIp[1] = 0x00.toByte()
        replyIp[2] = (replyTotalLen ushr 8).toByte()
        replyIp[3] = (replyTotalLen and 0xFF).toByte()
        replyIp[4] = 0x00
        replyIp[5] = 0x00
        replyIp[6] = 0x40.toByte()
        replyIp[7] = 0x00
        replyIp[8] = 64
        replyIp[9] = 17 // UDP

        // Swap src IP and dst IP
        System.arraycopy(packet, 16, replyIp, 12, 4)
        System.arraycopy(packet, 12, replyIp, 16, 4)

        computeIpChecksum(replyIp, 0, 20)

        // UDP header
        replyIp[20] = (dstPort ushr 8).toByte()
        replyIp[21] = (dstPort and 0xFF).toByte()
        replyIp[22] = (srcPort ushr 8).toByte()
        replyIp[23] = (srcPort and 0xFF).toByte()
        val replyUdpLen = 8 + replyDnsLen
        replyIp[24] = (replyUdpLen ushr 8).toByte()
        replyIp[25] = (replyUdpLen and 0xFF).toByte()
        replyIp[26] = 0x00
        replyIp[27] = 0x00

        // Copy DNS reply payload
        System.arraycopy(replyBuf, 0, replyIp, 28, replyDnsLen)

        synchronized(outStream) {
            try {
                outStream.write(replyIp, 0, replyTotalLen)
                outStream.flush()
            } catch (_: Exception) {}
        }
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int) {
        data[offset + 10] = 0
        data[offset + 11] = 0
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val w = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += w
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv()) and 0xFFFF
        data[offset + 10] = (checksum ushr 8).toByte()
        data[offset + 11] = (checksum and 0xFF).toByte()
    }

    private fun cleanupVpn() {
        isRunning.set(false)
        tunTcpHandler?.close()
        tunTcpHandler = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        if (VpnProtector.activeVpn === this) {
            VpnProtector.activeVpn = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_vpn_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, socksPort: Int, httpPort: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RelayVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val content = if (httpPort > 0) {
            getString(R.string.notif_vpn_content, socksPort, httpPort)
        } else {
            getString(R.string.notif_vpn_content_socks_only, socksPort)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_disconnect_vpn), pendingStop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String, socksPort: Int, httpPort: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(title, socksPort, httpPort))
    }

    override fun onDestroy() {
        try { cleanupVpn() } catch (_: Throwable) {}
        try { ProxyServerManager.stop() } catch (_: Throwable) {}
        try { serviceScope.cancel() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
