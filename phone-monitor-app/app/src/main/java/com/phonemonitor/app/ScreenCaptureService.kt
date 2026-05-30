package com.phonemonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.phonemonitor.app.data.ScreenFrameData
import com.phonemonitor.app.data.SystemStatusData
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 屏幕采集服务
 *
 * 使用 MediaProjection API 采集屏幕内容
 * 以 JPEG 格式压缩并通过 WebSocket 传输到电脑端
 * 默认每秒采集 5 帧（可配置）
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val VIRTUAL_DISPLAY_NAME = "PhoneMonitorCapture"
        private const val FRAME_INTERVAL_MS = 200L // 5 FPS

        // Intent action
        const val ACTION_START = "com.phonemonitor.action.START_CAPTURE"
        const val ACTION_STOP = "com.phonemonitor.action.STOP_CAPTURE"
        const val ACTION_UPDATE_WS = "com.phonemonitor.action.UPDATE_WS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WS_CLIENT = "ws_client"

        // 全局引用，便于其他组件访问
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var webSocketClient: WebSocketClient? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var frameCount = 0L
    private var isCapturing = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData = intent.getParcelableExtra(EXTRA_DATA)
                val wsUrl = intent.getStringExtra(EXTRA_WS_CLIENT)
                startCapture(wsUrl)
            }
            ACTION_STOP -> {
                stopCapture()
            }
            ACTION_UPDATE_WS -> {
                val wsUrl = intent.getStringExtra(EXTRA_WS_CLIENT)
                wsUrl?.let { updateWebSocket(it) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 启动屏幕采集
     */
    private fun startCapture(wsUrl: String?) {
        if (isRunning) return

        try {
            // 启动前台服务通知
            startForeground(NOTIFICATION_ID, createNotification())

            // 初始化 WebSocket
            initWebSocket(wsUrl)

            // 获取 MediaProjection
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)

            // 获取屏幕尺寸
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Log.i(TAG, "屏幕尺寸: ${width}x$height, 密度: $density")

            // 创建 ImageReader
            // 降低分辨率以提高性能（可配置）
            val captureWidth = width
            val captureHeight = height
            val maxDimension = 720 // 最大720p
            val finalWidth: Int
            val finalHeight: Int
            if (width > height && width > maxDimension) {
                finalWidth = maxDimension
                finalHeight = (height * maxDimension) / width
            } else if (height > maxDimension) {
                finalHeight = maxDimension
                finalWidth = (width * maxDimension) / height
            } else {
                finalWidth = width
                finalHeight = height
            }

            imageReader = ImageReader.newInstance(
                finalWidth, finalHeight,
                PixelFormat.RGBA_8888, 2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                captureAndSendFrame(reader, finalWidth!!, finalHeight!!)
            }, captureHandler)

            // 创建虚拟显示
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                finalWidth, finalHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )

            // 启动采集循环
            captureHandlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
            captureHandler = Handler(captureHandlerThread!!.looper)

            isCapturing = true
            isRunning = true

            Log.i(TAG, "屏幕采集已启动: ${finalWidth}x${finalHeight}")

            // 发送状态
            sendStatus()

        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕采集失败: ${e.message}", e)
            isRunning = false
            stopSelf()
        }
    }

    /**
     * 采集并发送帧
     */
    private fun captureAndSendFrame(reader: ImageReader, width: Int, height: Int) {
        if (!isCapturing) return

        val image = reader.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉padding
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                sendFrame(cropped)
            } else {
                sendFrame(bitmap)
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理帧失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * 发送帧到 WebSocket
     */
    private fun sendFrame(bitmap: Bitmap) {
        try {
            val stream = ByteArrayOutputStream()
            // JPEG 压缩：质量 70 平衡画质和速度
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            val imageBytes = stream.toByteArray()
            val base64Str = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            frameCount++
            val frameData = ScreenFrameData(
                frameNumber = frameCount,
                imageDataBase64 = base64Str,
                width = bitmap.width,
                height = bitmap.height
            )

            webSocketClient?.sendScreenFrame(frameData)

            bitmap.recycle()
            stream.close()
        } catch (e: Exception) {
            Log.w(TAG, "发送帧失败: ${e.message}")
        }
    }

    /**
     * 停止采集
     */
    private fun stopCapture() {
        isCapturing = false
        isRunning = false

        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            captureHandlerThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "释放资源时出错: ${e.message}")
        }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureHandlerThread = null
        captureHandler = null

        sendStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "屏幕采集已停止，共采集 $frameCount 帧")
    }

    /**
     * 初始化 WebSocket
     */
    private fun initWebSocket(wsUrl: String?) {
        if (webSocketClient != null) return
        webSocketClient = PhoneMonitorApplication.getWebSocketClient()
        if (wsUrl != null && wsUrl.isNotBlank()) {
            webSocketClient?.setServerUrl(wsUrl)
        } else {
            webSocketClient?.connect()
        }
    }

    private fun updateWebSocket(wsUrl: String) {
        webSocketClient?.setServerUrl(wsUrl)
    }

    /**
     * 发送系统状态
     */
    private fun sendStatus() {
        val status = SystemStatusData(
            screenCaptureEnabled = isRunning,
            chatMonitorEnabled = ChatMonitorService.isRunning,
            notificationMonitorEnabled = NotificationMonitorService.isRunning,
            webSocketConnected = webSocketClient?.connectionState?.value == WebSocketClient.ConnectionState.CONNECTED
        )
        webSocketClient?.sendStatus(status)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕采集",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕采集服务运行状态"
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕采集运行中")
            .setContentText("正在采集屏幕并传输到电脑端")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
