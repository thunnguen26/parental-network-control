package com.parental.android.service

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
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.parental.android.R
import com.parental.android.data.ConnectionSettingsStore
import com.parental.android.device.AndroidDeviceInfoProvider
import com.parental.android.network.AndroidTcpClientEngine
import com.parental.android.network.ClientConfig
import com.parental.android.network.ConnectionState
import com.parental.android.network.ServerEventListener
import com.parental.android.protocol.CommandPayload
import com.parental.android.protocol.ConfigSyncPayload
import kotlinx.coroutines.launch

/**
 * Foreground Service giữ kết nối TCP sống khi app ẩn (Android kill background
 * socket nhưng KHÔNG kill foreground service có notification hiển thị — yêu
 * cầu nhiệm vụ Mục 4). Service:
 *
 * - START: đọc settings + device info → dựng engine → start; notification
 *   trạng thái cập nhật realtime qua [onConnectionState].
 * - STOP: nút "Ngắt kết nối" gửi intent ACTION_STOP → engine.stop().
 * - Command/ConfigSync hiện chỉ LOG (khung mở rộng 13 — enforcement Android
 *   như VPN Service là phần tiếp theo của ROADMAP).
 */
class ConnectionForegroundService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "connection_status"
        const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.parental.android.START_CONNECTION"
        const val ACTION_STOP = "com.parental.android.STOP_CONNECTION"

        /** Host/port gửi kèm intent khi bấm Kết nối. */
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        /** Trạng thái mới nhất để UI hiển thị (in-memory — service đơn thể). */
        @Volatile
        var lastState: ConnectionState = ConnectionState.DISCONNECTED
            private set

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, ConnectionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ConnectionForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }

    private var engine: AndroidTcpClientEngine? = null
    private lateinit var settingsStore: ConnectionSettingsStore

    override fun onCreate() {
        super.onCreate()
        settingsStore = ConnectionSettingsStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, ConnectionSettingsStore.DEFAULT_PORT)
                startEngineInBackground(host, port)
            }
        }
        return START_STICKY // hệ thống kill → tự khởi động lại
    }

    private fun startEngineInBackground(host: String, port: Int) {
        if (engine != null) {
            updateNotification(buildNotification(host, ConnectionState.CONNECTING))
            return // đã chạy — chỉ refresh notification
        }
        startForegroundWithNotification(host)

        lifecycleScope.launch {
            val settings = settingsStore.current()
            val clientId = settingsStore.getOrCreateClientId()
            val info = AndroidDeviceInfoProvider.collect(applicationContext)

            val config = ClientConfig(
                host = host,
                port = port,
                clientId = clientId,
                deviceName = info.deviceName,
                deviceType = info.deviceType,
                osVersion = info.osVersion,
                ipAddress = info.ipAddress,
                macAddress = info.macAddress,
                deviceToken = settings.deviceToken
            )
            val newEngine = AndroidTcpClientEngine(config, object : ServerEventListener {
                override fun onCommand(command: CommandPayload) {
                    // Mở rộng 13: chưa thực thi enforcement — ghi log; ACK do engine gửi
                    android.util.Log.i("ParentalAndroid", "COMMAND received: ${command.action} (${command.reason})")
                }

                override fun onConfigSync(config: ConfigSyncPayload) {
                    android.util.Log.i(
                        "ParentalAndroid",
                        "CONFIG_SYNC received: ${config.blockedDomains.size} domains, ${config.blockedProcesses.size} processes"
                    )
                }

                override fun onUnpair(reason: String) {
                    android.util.Log.w("ParentalAndroid", "UNPAIR: $reason")
                    lifecycleScope.launch { settingsStore.clearDeviceToken() }
                }

                override fun onConnectionState(state: ConnectionState) {
                    lastState = state
                    updateNotification(buildNotification(host, state))
                }
            })
            engine = newEngine
            newEngine.start()
        }
    }

    private fun shutdownEngine() {
        engine?.stop()
        engine = null
        lastState = ConnectionState.DISCONNECTED
    }

    override fun onDestroy() {
        shutdownEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(host: String) {
        val notification = buildNotification(host, ConnectionState.CONNECTING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(host: String, state: ConnectionState): Notification {
        val contentText = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.notif_connected, host)
            ConnectionState.CONNECTING -> getString(R.string.notif_connecting, host)
            ConnectionState.DISCONNECTED -> getString(R.string.notif_disconnected, host)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // không beep mỗi lần refresh trạng thái
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
