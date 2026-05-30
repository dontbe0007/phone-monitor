# 手机监控 ProGuard 配置

# Keep data classes for Gson serialization
-keepclassmembers class com.phonemonitor.app.data.** {
    *;
}

# Keep WebSocket classes
-keep class com.phonemonitor.app.WebSocketClient { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
