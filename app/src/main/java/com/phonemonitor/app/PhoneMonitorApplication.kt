package com.phonemonitor.app

import android.app.Application

/**
 * 应用 Application 类
 *
 * 提供全局 WebSocket 客户端实例
 */
class PhoneMonitorApplication : Application() {

    companion object {
        private lateinit var instance: PhoneMonitorApplication
        private var wsClient: WebSocketClient? = null

        fun getWebSocketClient(): WebSocketClient {
            if (wsClient == null) {
                wsClient = WebSocketClient("ws://10.0.2.2:9090/monitor")
            }
            return wsClient!!
        }

        fun getInstance(): PhoneMonitorApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onTerminate() {
        super.onTerminate()
        wsClient?.shutdown()
    }
}
