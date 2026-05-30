package com.phonemonitor.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phonemonitor.app.data.ChatCaptureData
import com.phonemonitor.app.data.ChatMessage
import com.phonemonitor.app.data.MessageType

/**
 * 聊天监控无障碍服务
 *
 * 通过 AccessibilityService 读取屏幕上的所有文本内容
 * 实时捕获聊天记录（微信、QQ、短信等任何 App 的文本内容）
 *
 * 工作原理：
 * 1. 监听窗口状态变化（TYPE_WINDOW_STATE_CHANGED）
 * 2. 当检测到聊天/消息类 App 在前台时，采集屏幕上的所有文本
 * 3. 通过 WebSocket 发送到电脑端
 *
 * 注意：这读取的是屏幕上显示的所有文本，不是数据库记录
 * 仅在 App 处于前台时采集，不后台读取历史记录
 */
class ChatMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatMonitor"
        private const val MIN_COLLECTION_INTERVAL_MS = 2000L // 最小采集间隔，避免过于频繁

        var isRunning = false
            private set

        // 已知的聊天 App 包名（用于优化采集时机）
        private val CHAT_APPS = setOf(
            "com.tencent.mm",        // 微信
            "com.tencent.mobileqq",  // QQ
            "com.tencent.tim",       // TIM
            "com.tencent.wework",    // 企业微信
            "com.eg.android.AlipayGphone", // 支付宝
            "com.android.mms",       // 系统短信
            "com.google.android.apps.messaging", // Google 消息
            "com.whatsapp",          // WhatsApp
            "org.telegram.messenger",// Telegram
            "com.zhihu.android",     // 知乎
            "com.sina.weibo",        // 微博
            "com.taobao.taobao",     // 淘宝
            "com.example.chat"       // 示例
        )
    }

    private var webSocketClient: WebSocketClient? = null
    private var lastCollectionTime = 0L
    private var lastPackageName = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        webSocketClient = PhoneMonitorApplication.getWebSocketClient()

        Log.i(TAG, "聊天监控无障碍服务已连接")

        // 配置服务信息
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

            notificationTimeout = 500
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return

        try {
            val packageName = event.packageName?.toString() ?: return

            // 只处理用户可感知的事件类型
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowChange(event, packageName)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleContentChange(event, packageName)
                }
                else -> {
                    // 其他事件忽略
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理无障碍事件出错: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    /**
     * 处理窗口切换事件
     * 当 App 切换时采集其内容
     */
    private fun handleWindowChange(event: AccessibilityEvent, packageName: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCollectionTime < MIN_COLLECTION_INTERVAL_MS) return
        if (packageName == lastPackageName && packageName !in CHAT_APPS) return

        lastPackageName = packageName
        lastCollectionTime = currentTime

        val appName = getAppName(packageName)
        Log.d(TAG, "窗口切换: $appName ($packageName)")

        // 延迟一点等窗口渲染完成
        rootInActiveWindow?.let { root ->
            collectScreenContent(root, packageName, appName)
        }
    }

    /**
     * 处理内容变化事件
     * 只在聊天类 App 中采集内容变化
     */
    private fun handleContentChange(event: AccessibilityEvent, packageName: String) {
        if (packageName !in CHAT_APPS) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCollectionTime < MIN_COLLECTION_INTERVAL_MS) return

        lastCollectionTime = currentTime

        val appName = getAppName(packageName)
        Log.d(TAG, "内容变化: $appName")

        rootInActiveWindow?.let { root ->
            collectScreenContent(root, packageName, appName)
        }
    }

    /**
     * 采集屏幕上的所有文本内容
     */
    private fun collectScreenContent(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        appName: String
    ) {
        try {
            val allText = mutableListOf<String>()
            val messages = mutableListOf<ChatMessage>()

            // 递归遍历所有节点获取文本
            collectTextNodes(rootNode, allText)

            // 如果文本太少，可能不是聊天界面
            if (allText.size < 2) return

            // 尝试解析聊天消息（按行或聊天泡分组）
            collectChatMessages(rootNode, messages)

            val screenText = allText.joinToString("\n")

            if (screenText.isBlank()) return

            val captureData = ChatCaptureData(
                packageName = packageName,
                appName = appName,
                messages = messages,
                screenText = screenText
            )

            webSocketClient?.sendChatData(captureData)
            Log.i(TAG, "已采集 $appName 屏幕文本: ${allText.size} 个节点, ${messages.size} 条消息")

        } catch (e: Exception) {
            Log.w(TAG, "采集屏幕内容出错: ${e.message}")
        }
    }

    /**
     * 递归遍历节点树，收集所有文本
     */
    private fun collectTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        // 获取节点文本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                texts.add(it.trim())
            }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                texts.add("[描述] $it")
            }
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    collectTextNodes(child, texts)
                } finally {
                    // 注意：不要在遍历时回收子节点
                }
            }
        }
    }

    /**
     * 尝试从屏幕上解析聊天消息
     * 通过识别常见的聊天 UI 元素来提取消息
     */
    private fun collectChatMessages(node: AccessibilityNodeInfo, messages: MutableList<ChatMessage>) {
        // 检测聊天消息泡
        val className = node.className?.toString() ?: ""

        // 尝试识别消息泡
        val isMessageBubble = className.contains("TextView") ||
                className.contains("LinearLayout") ||
                className.contains("FrameLayout")

        if (isMessageBubble && node.text != null) {
            val text = node.text.toString().trim()
            if (text.isNotBlank() && text.length > 1) {
                messages.add(
                    ChatMessage(
                        appName = "unknown",
                        sender = "屏幕文本",
                        content = text,
                        type = MessageType.TEXT
                    )
                )
            }
        }

        // 继续遍历子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    collectChatMessages(child, messages)
                } catch (e: Exception) {
                    // 忽略单个节点的错误
                }
            }
        }
    }

    /**
     * 根据包名获取 App 显示名称
     */
    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            when (packageName) {
                "com.tencent.mm" -> "微信"
                "com.tencent.mobileqq" -> "QQ"
                "com.android.mms" -> "短信"
                "com.tencent.wework" -> "企业微信"
                "com.whatsapp" -> "WhatsApp"
                "org.telegram.messenger" -> "Telegram"
                else -> packageName
            }
        }
    }

    /**
     * 检查指定包名是否为已知的聊天 App
     */
    fun isChatApp(packageName: String): Boolean {
        return packageName in CHAT_APPS
    }
}
