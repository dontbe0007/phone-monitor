package com.phonemonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 主 Activity
 *
 * 手机监控 App 的控制面板
 * 使用 Jetpack Compose 构建 UI
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1000
    }

    private val viewModel = MainViewModel()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "权限 $permission: $granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求必要的权限
        requestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    secondary = Color(0xFF80CBC4),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                PhoneMonitorApp(viewModel)
            }
        }
    }

    /**
     * 请求运行所需的权限
     */
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要运行时通知权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 读取短信权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_SMS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * 处理屏幕采集授权结果
     */
    @Deprecated("使用 ActivityResultLauncher 替代")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            viewModel.handleScreenCaptureResult(resultCode, data, this)
        }
    }
}

/**
 * 主界面 Composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneMonitorApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📱 手机监控", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (uiState.wsConnected) "🟢 已连接" else if (uiState.wsReconnecting) "🟡 重连中..." else "🔴 未连接",
                            fontSize = 12.sp,
                            color = if (uiState.wsConnected) Color(0xFF4CAF50)
                            else if (uiState.wsReconnecting) Color(0xFFFFC107)
                            else Color(0xFFF44336)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // WebSocket 连接配置
            WebSocketConfigSection(uiState, viewModel, context)

            // 服务控制面板
            ServiceControlPanel(uiState, viewModel, context)

            // 操作日志
            NotificationLog(uiState.notifications)
        }
    }
}

/**
 * WebSocket 配置区域
 */
@Composable
fun WebSocketConfigSection(
    uiState: MainViewModel.UiState,
    viewModel: MainViewModel,
    context: android.content.Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🔗 WebSocket 连接",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            var wsUrl by remember { mutableStateOf(uiState.wsUrl) }

            OutlinedTextField(
                value = wsUrl,
                onValueChange = { wsUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text("ws://192.168.1.100:9090/monitor") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        viewModel.setWsUrl(wsUrl)
                        viewModel.connectWebSocket()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.setWsUrl(wsUrl)
                        viewModel.connectWebSocket()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("连接")
                }

                OutlinedButton(
                    onClick = { viewModel.disconnectWebSocket() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("断开")
                }
            }
        }
    }
}

/**
 * 服务控制面板
 */
@Composable
fun ServiceControlPanel(
    uiState: MainViewModel.UiState,
    viewModel: MainViewModel,
    context: android.content.Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🎛️ 服务控制",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 屏幕采集
            ServiceToggle(
                icon = Icons.Default.Videocam,
                title = "屏幕采集",
                description = "实时采集手机屏幕投屏到电脑",
                isEnabled = uiState.isScreenCaptureEnabled,
                requiresSetup = false,
                onToggle = {
                    if (uiState.isScreenCaptureEnabled) {
                        viewModel.stopScreenCapture(context)
                    } else {
                        if (context is android.app.Activity) {
                            viewModel.requestScreenCapture(context)
                        }
                    }
                },
                onSetupClick = {}
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 聊天监控（无障碍服务）
            val accessibilityEnabled = viewModel.isAccessibilityServiceEnabled(context)
            ServiceToggle(
                icon = Icons.Default.Chat,
                title = "聊天监控",
                description = "通过无障碍服务读取聊天记录",
                isEnabled = uiState.isChatMonitorEnabled,
                requiresSetup = !accessibilityEnabled,
                onToggle = {
                    if (uiState.isChatMonitorEnabled) {
                        viewModel.disableChatMonitor()
                    } else {
                        viewModel.enableChatMonitor(context)
                    }
                },
                onSetupClick = {
                    viewModel.openAccessibilitySettings(context)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 通知监听
            val notifListenerEnabled = viewModel.isNotificationListenerEnabled(context)
            ServiceToggle(
                icon = Icons.Default.Notifications,
                title = "通知监听",
                description = "监听所有 App 的实时通知",
                isEnabled = uiState.isNotificationMonitorEnabled,
                requiresSetup = !notifListenerEnabled,
                onToggle = {
                    if (uiState.isNotificationMonitorEnabled) {
                        // 通知监听服务无法从代码停止，只能禁用
                    } else {
                        if (notifListenerEnabled) {
                            viewModel.enableNotificationMonitor(context)
                        }
                    }
                },
                onSetupClick = {
                    viewModel.openNotificationListenerSettings(context)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 发送状态按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { viewModel.sendStatus() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("发送状态")
                }
            }
        }
    }
}

/**
 * 单个服务的开关控制
 */
@Composable
fun ServiceToggle(
    icon: ImageVector,
    title: String,
    description: String,
    isEnabled: Boolean,
    requiresSetup: Boolean,
    onToggle: () -> Unit,
    onSetupClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 文字说明
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isEnabled) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                }
            }
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (requiresSetup && !isEnabled) {
                Text(
                    text = "⚠️ 需要先在系统设置中开启",
                    fontSize = 11.sp,
                    color = Color(0xFFFFC107)
                )
            }
        }

        // 操作按钮
        if (requiresSetup && !isEnabled) {
            TextButton(onClick = onSetupClick) {
                Text("去设置", fontSize = 13.sp)
            }
        } else {
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}

/**
 * 通知日志列表
 */
@Composable
fun NotificationLog(notifications: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📋 操作日志",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (notifications.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    Text(
                        text = "${notifications.size} 条",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "操作日志将在此显示",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(notifications.reversed()) { notif ->
                        Text(
                            text = notif,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 深色配色方案
 */
private fun darkColorScheme(
    primary: Color,
    secondary: Color,
    background: Color,
    surface: Color,
    onPrimary: Color,
    onSecondary: Color,
    onBackground: Color,
    onSurface: Color
) = androidx.compose.material3.darkColorScheme(
    primary = primary,
    secondary = secondary,
    tertiary = Color(0xFFFFB74D),
    background = background,
    surface = surface,
    onPrimary = onPrimary,
    onSecondary = onSecondary,
    onBackground = onBackground,
    onSurface = onSurface,
    surfaceVariant = surface.copy(alpha = 0.8f),
    onSurfaceVariant = onSurface.copy(alpha = 0.7f),
    outline = onSurface.copy(alpha = 0.2f)
)
