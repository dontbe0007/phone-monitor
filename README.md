# 📱 手机监控 App (Phone Monitor)

一个自用的安卓手机监控应用，通过 WebSocket 将手机屏幕、聊天记录、通知实时传输到电脑端查看。

## 功能

1. **实时屏幕投屏** - 使用 MediaProjection API 采集屏幕内容，JPEG 压缩后传输到电脑
2. **聊天记录监控** - 通过 AccessibilityService 读取屏幕上所有 App 的文本内容（微信、QQ、短信等）
3. **通知实时监听** - 通过 NotificationListenerService 获取所有 App 的实时通知
4. **WebSocket 传输** - 所有数据通过 WebSocket 实时传输到电脑端

## 项目结构

```
phone-monitor-app/
├── app/                          # Android 应用
│   ├── build.gradle.kts          # 构建配置
│   └── src/main/
│       ├── AndroidManifest.xml   # 清单文件
│       ├── java/com/phonemonitor/app/
│       │   ├── MainActivity.kt           # 主界面 (Jetpack Compose)
│       │   ├── MainViewModel.kt          # UI 逻辑
│       │   ├── PhoneMonitorApplication.kt # Application 类
│       │   ├── ScreenCaptureService.kt   # 屏幕采集服务
│       │   ├── ChatMonitorService.kt     # 聊天监控无障碍服务
│       │   ├── NotificationMonitorService.kt # 通知监听服务
│       │   ├── WebSocketClient.kt        # WebSocket 客户端
│       │   └── data/
│       │       └── ChatMessage.kt        # 数据模型
│       └── res/
│           ├── xml/accessibility_service_config.xml
│           └── values/
├── desktop-viewer/               # 电脑端查看器
│   ├── server.js                 # WebSocket + Express 服务器
│   ├── package.json
│   └── public/
│       └── index.html            # 网页查看器
└── build.gradle.kts
```

## 使用方法

### 1. 电脑端启动查看器

```bash
cd desktop-viewer
npm install
node server.js
```

浏览器打开 `http://localhost:9090`

### 2. 手机端

1. 用 Android Studio 打开项目，编译安装到手机
2. 在 App 中设置电脑端地址：`ws://你的电脑IP:9090/monitor`
3. 开启各功能（需要授予相应权限）

### 3. 权限说明

| 权限 | 用途 |
|------|------|
| MediaProjection | 屏幕采集 |
| AccessibilityService | 聊天记录读取 |
| NotificationListenerService | 通知监听 |
| INTERNET | WebSocket 传输 |
| FOREGROUND_SERVICE | 后台运行 |
| READ_SMS | 短信读取 |
| POST_NOTIFICATIONS | 通知权限 (Android 13+) |

## 技术栈

- **手机端**: Kotlin + Jetpack Compose + OkHttp WebSocket
- **电脑端**: Node.js + ws + Express + SSE
- **传输**: WebSocket (JSON + Base64 JPEG)
- **API**: MediaProjection, AccessibilityService, NotificationListenerService

## 注意

- 本应用仅供自己使用
- 聊天记录监控通过读取屏幕显示内容实现，不是直接读取数据库
- 屏幕采集会降低分辨率到 720p 以优化传输性能
- 确保手机和电脑在同一个局域网，或通过公网 IP 连接
