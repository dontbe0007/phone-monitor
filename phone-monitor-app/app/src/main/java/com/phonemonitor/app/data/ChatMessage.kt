package com.phonemonitor.app.data

/**
 * 聊天消息数据模型
 */
data class ChatMessage(
    val id: String = "${System.currentTimeMillis()}",
    val appName: String,          // 来源App（微信、QQ、短信等）
    val sender: String,           // 发送者
    val content: String,          // 消息内容（可能包含表情符号文本表示）
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT
)

enum class MessageType {
    TEXT,
    IMAGE,
    SYSTEM_NOTIFICATION,
    UNKNOWN
}

/**
 * 聊天记录采集的数据包
 */
data class ChatCaptureData(
    val type: String = "chat_message",
    val packageName: String,      // 来源App包名
    val appName: String,          // App名称
    val messages: List<ChatMessage>,
    val screenText: String,       // 当前屏幕的所有文本内容（原始）
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 屏幕采集帧数据（JPEG压缩后）
 */
data class ScreenFrameData(
    val type: String = "screen_frame",
    val frameNumber: Long,
    val imageDataBase64: String,  // JPEG图片的Base64编码
    val width: Int,
    val height: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 通知数据
 */
data class NotificationData(
    val type: String = "notification",
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 系统状态数据包
 */
data class SystemStatusData(
    val type: String = "system_status",
    val screenCaptureEnabled: Boolean,
    val chatMonitorEnabled: Boolean,
    val notificationMonitorEnabled: Boolean,
    val webSocketConnected: Boolean,
    val connectedClients: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * WebSocket 命令（从电脑端发往手机端）
 */
data class WsCommand(
    val action: String,   // "start_screen_capture", "stop_screen_capture", "start_chat_monitor", "stop_chat_monitor", "request_status"
    val params: Map<String, Any>? = null
)

/**
 * 通用 WebSocket 消息包装
 */
data class WsMessage(
    val type: String,
    val payload: Any? = null
)
