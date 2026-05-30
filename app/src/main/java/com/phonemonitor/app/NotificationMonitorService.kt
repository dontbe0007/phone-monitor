package com.phonemonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phonemonitor.app.data.NotificationData

/**
 * 通知监听服务
 *
 * 通过 NotificationListenerService 获取手机上所有 App 的实时通知
 * 将通知数据传输到 WebSocket 客户端
 */
class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationMonitor"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "notification_monitor_channel"

        var isRunning = false
            private set
    }

    private var webSocketClient: WebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true

        // 尝试作为前台服务启动（需要 API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听服务已连接")
        webSocketClient = PhoneMonitorApplication.getWebSocketClient()

        // 发送当前已有的通知
        for (sbn in activeNotifications) {
            processNotification(sbn, true)
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "通知监听服务断开连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn, false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.d(TAG, "通知已移除: ${sbn.packageName}")
    }

    /**
     * 处理通知数据并发送
     */
    private fun processNotification(sbn: StatusBarNotification, isExisting: Boolean) {
        try {
            val notification = sbn.notification
            val extras = notification.extras ?: return

            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            } else {
                @Suppress("DEPRECATION")
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            }

            // 过滤掉自己发出的通知
            if (sbn.packageName == packageName) return
            if (title.isEmpty() && text.isEmpty()) return

            val appName = getAppName(sbn.packageName)

            val notifData = NotificationData(
                packageName = sbn.packageName,
                appName = appName,
                title = if (isExisting) "[已有] $title" else title,
                text = text,
                timestamp = sbn.postTime
            )

            webSocketClient?.sendNotification(notifData)
            Log.d(TAG, "通知: [$appName] $title: $text")

        } catch (e: Exception) {
            Log.w(TAG, "处理通知时出错: ${e.message}")
        }
    }

    /**
     * 根据包名获取 App 名称
     */
    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "通知监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通知监听服务运行状态"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通知监听运行中")
            .setContentText("正在监听手机上的通知消息")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
