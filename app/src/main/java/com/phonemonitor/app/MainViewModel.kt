package com.phonemonitor.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonemonitor.app.data.SystemStatusData
import com.phonemonitor.app.data.WsCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel
 */
class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1000
    }

    // UI 状态
    data class UiState(
        val wsUrl: String = "ws://10.0.2.2:9090/monitor",
        val isScreenCaptureEnabled: Boolean = false,
        val isChatMonitorEnabled: Boolean = false,
        val isNotificationMonitorEnabled: Boolean = false,
        val wsConnected: Boolean = false,
        val wsReconnecting: Boolean = false,
        val notifications: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val webSocketClient = PhoneMonitorApplication.getWebSocketClient()

    init {
        // 监听 WebSocket 连接状态
        viewModelScope.launch {
            webSocketClient.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    wsConnected = state == WebSocketClient.ConnectionState.CONNECTED,
                    wsReconnecting = state == WebSocketClient.ConnectionState.RECONNECTING
                )
            }
        }

        // 监听来自 PC 端的命令
        viewModelScope.launch {
            webSocketClient.commands.collect { command ->
                handleCommand(command)
            }
        }
    }

    /**
     * 处理从 WebSocket 收到的命令
     */
    private fun handleCommand(command: WsCommand) {
        Log.i(TAG, "收到命令: ${command.action}")
        when (command.action) {
            "start_screen_capture" -> {
                // 屏幕采集需要 Activity 发起 Intent，这里只是标记
                // 实际由 UI 层触发
            }
            "stop_screen_capture" -> {
                stopScreenCapture()
            }
            "start_chat_monitor" -> {
                enableChatMonitor()
            }
            "stop_chat_monitor" -> {
                disableChatMonitor()
            }
            "request_status" -> {
                sendStatus()
            }
        }
    }

    /**
     * 请求屏幕采集权限并启动
     */
    fun requestScreenCapture(activity: Activity) {
        val mediaProjectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val intent = mediaProjectionManager.createScreenCaptureIntent()

        // 使用 ActivityResultLauncher 或 startActivityForResult
        // 这里通过 startActivityForResult 的兼容方式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
        } else {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
        }
    }

    /**
     * 处理屏幕采集授权结果
     */
    fun handleScreenCaptureResult(resultCode: Int, data: Intent?, activity: Activity) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data, activity)
        } else {
            addNotification("屏幕采集权限被拒绝")
        }
    }

    /**
     * 启动屏幕采集服务
     */
    private fun startScreenCapture(resultCode: Int, data: Intent?, activity: Activity) {
        val serviceIntent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WS_CLIENT, _uiState.value.wsUrl)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(serviceIntent)
        } else {
            @Suppress("DEPRECATION")
            activity.startService(serviceIntent)
        }

        _uiState.value = _uiState.value.copy(isScreenCaptureEnabled = true)
        addNotification("屏幕采集已启动")
    }

    /**
     * 停止屏幕采集
     */
    fun stopScreenCapture(context: Context? = null) {
        val ctx = context ?: PhoneMonitorApplication.getInstance()
        val stopIntent = Intent(ctx, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        ctx.startService(stopIntent)
        _uiState.value = _uiState.value.copy(isScreenCaptureEnabled = false)
        addNotification("屏幕采集已停止")
    }

    /**
     * 检查无障碍服务是否启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        addNotification("请在设置中开启「聊天监控」无障碍服务")
    }

    /**
     * 启用聊天监控
     */
    fun enableChatMonitor(context: Context? = null) {
        _uiState.value = _uiState.value.copy(isChatMonitorEnabled = true)
        addNotification("聊天监控已启用")
    }

    /**
     * 禁用聊天监控
     */
    fun disableChatMonitor() {
        _uiState.value = _uiState.value.copy(isChatMonitorEnabled = false)
        addNotification("聊天监控已禁用")
    }

    /**
     * 检查通知监听服务是否启用
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
        )
        return flat?.contains(packageName) == true
    }

    /**
     * 打开通知监听设置页面
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        addNotification("请在设置中开启「通知监听」服务")
    }

    /**
     * 启动通知监听服务
     */
    fun enableNotificationMonitor(context: Context) {
        val intent = Intent(context, NotificationMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(intent)
        }
        _uiState.value = _uiState.value.copy(isNotificationMonitorEnabled = true)
        addNotification("通知监听已启动")
    }

    /**
     * 设置 WebSocket 服务器地址
     */
    fun setWsUrl(url: String) {
        _uiState.value = _uiState.value.copy(wsUrl = url)
        webSocketClient.setServerUrl(url)
    }

    /**
     * 连接 WebSocket
     */
    fun connectWebSocket() {
        webSocketClient.connect(_uiState.value.wsUrl)
    }

    /**
     * 断开 WebSocket
     */
    fun disconnectWebSocket() {
        webSocketClient.disconnect()
    }

    /**
     * 发送系统状态
     */
    fun sendStatus() {
        webSocketClient.sendStatus(
            SystemStatusData(
                screenCaptureEnabled = _uiState.value.isScreenCaptureEnabled,
                chatMonitorEnabled = _uiState.value.isChatMonitorEnabled,
                notificationMonitorEnabled = _uiState.value.isNotificationMonitorEnabled,
                webSocketConnected = _uiState.value.wsConnected
            )
        )
    }

    /**
     * 添加通知消息
     */
    private fun addNotification(msg: String) {
        val current = _uiState.value.notifications.toMutableList()
        current.add("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} - $msg")
        if (current.size > 50) {
            current.removeAt(0)
        }
        _uiState.value = _uiState.value.copy(notifications = current)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
