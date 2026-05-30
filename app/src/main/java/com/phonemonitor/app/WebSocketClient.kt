package com.phonemonitor.app

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.phonemonitor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端 - 负责与电脑端服务器通信
 *
 * 使用 OkHttp WebSocket 实现双向通信
 * 自动重连机制
 */
class WebSocketClient(
    private val serverUrl: String = "ws://10.0.2.2:9090/monitor" // 默认Android模拟器宿主机
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 100
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 不需要超时
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)     // 心跳保活
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // 收到的命令流
    private val _commands = MutableSharedFlow<WsCommand>(extraBufferCapacity = 10)
    val commands: SharedFlow<WsCommand> = _commands

    // WebSocket 监听器
    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket 已连接: ${response.code}")
            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "收到消息: $text")
            try {
                val command = gson.fromJson(text, WsCommand::class.java)
                scope.launch {
                    _commands.emit(command)
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法解析命令: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 错误: ${t.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 关闭: $code $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 正在关闭: $code $reason")
            webSocket.close(code, reason)
        }
    }

    /**
     * 连接到 WebSocket 服务器
     */
    fun connect(url: String? = null) {
        val targetUrl = url ?: serverUrl
        Log.i(TAG, "正在连接 WebSocket: $targetUrl")
        shouldReconnect = true
        val request = Request.Builder()
            .url(targetUrl)
            .build()
        webSocket = client.newWebSocket(request, wsListener)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "客户端主动断开")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 发送聊天记录数据
     */
    fun sendChatData(data: ChatCaptureData) {
        sendJson(gson.toJson(data))
    }

    /**
     * 发送屏幕帧数据
     */
    fun sendScreenFrame(data: ScreenFrameData) {
        sendJson(gson.toJson(data))
    }

    /**
     * 发送通知数据
     */
    fun sendNotification(data: NotificationData) {
        sendJson(gson.toJson(data))
    }

    /**
     * 发送系统状态
     */
    fun sendStatus(data: SystemStatusData) {
        sendJson(gson.toJson(data))
    }

    /**
     * 发送 JSON 字符串
     */
    private fun sendJson(json: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "WebSocket 未连接，消息已丢弃")
            return false
        }
        return ws.send(json)
    }

    /**
     * 自动重连
     */
    private fun attemptReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return

        reconnectAttempts++
        val delay = minOf(RECONNECT_DELAY_MS * reconnectAttempts, 30_000L)
        Log.i(TAG, "将在 ${delay}ms 后重连 (尝试 $reconnectAttempts)")

        scope.launch {
            delay(delay)
            _connectionState.value = ConnectionState.RECONNECTING
            connect()
        }
    }

    /**
     * 设置服务器地址（用于UI配置）
     */
    fun setServerUrl(url: String) {
        disconnect()
        connect(url)
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        shouldReconnect = false
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }
}
