/**
 * 手机监控 - 桌面端 WebSocket 服务器
 *
 * 功能：
 * 1. WebSocket 服务器接收手机端数据（屏幕帧、聊天记录、通知）
 * 2. Express 静态文件服务提供网页查看器
 * 3. 通过 Server-Sent Events (SSE) 实时推送到浏览器
 *
 * 使用方法：
 * 1. npm install
 * 2. node server.js
 * 3. 浏览器打开 http://localhost:9090
 * 4. 手机上连接 ws://电脑IP:9090/monitor
 */

const http = require('http');
const path = require('path');
const fs = require('fs');
const { WebSocketServer } = require('ws');

// ============ 配置 ============
const PORT = 9090;
const MAX_SCREEN_FRAMES = 50;  // 最多保存的屏幕帧数
const MAX_CHAT_MESSAGES = 200; // 最多保存的聊天记录条数
const MAX_NOTIFICATIONS = 100; // 最多保存的通知条数

// ============ 数据存储 ============
let latestScreenFrame = null;
let screenFrames = [];
let chatMessages = [];
let notifications = [];
let connectedClients = 0;
let connectionStatus = {
    phoneConnected: false,
    screenCaptureEnabled: false,
    chatMonitorEnabled: false,
    notificationMonitorEnabled: false,
    connectedAt: null
};

// SSE 客户端连接池
const sseClients = new Set();

// ============ HTTP 服务器 ============
const server = http.createServer((req, res) => {
    // CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    const url = new URL(req.url, `http://${req.headers.host}`);

    // SSE 端点 - 用于实时推送数据到浏览器
    if (url.pathname === '/events') {
        handleSSE(req, res);
        return;
    }

    // API 端点 - 获取最新数据
    if (url.pathname === '/api/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            ...connectionStatus,
            connectedClients,
            frameCount: screenFrames.length,
            chatCount: chatMessages.length,
            notificationCount: notifications.length
        }));
        return;
    }

    if (url.pathname === '/api/screen-frames') {
        const count = parseInt(url.searchParams.get('count') || '10');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(screenFrames.slice(-count)));
        return;
    }

    if (url.pathname === '/api/chat-messages') {
        const count = parseInt(url.searchParams.get('count') || '50');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(chatMessages.slice(-count)));
        return;
    }

    if (url.pathname === '/api/notifications') {
        const count = parseInt(url.searchParams.get('count') || '50');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(notifications.slice(-count)));
        return;
    }

    // 发送命令到手机
    if (url.pathname === '/api/command' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
            try {
                const command = JSON.parse(body);
                sendCommandToPhone(command);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: true }));
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: e.message }));
            }
        });
        return;
    }

    // 清除聊天记录
    if (url.pathname === '/api/clear-chat') {
        chatMessages = [];
        broadcastToSSEClients({ type: 'chat_cleared' });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
        return;
    }

    // 清除通知
    if (url.pathname === '/api/clear-notifications') {
        notifications = [];
        broadcastToSSEClients({ type: 'notifications_cleared' });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
        return;
    }

    // 静态文件服务
    let filePath = url.pathname === '/' ? '/index.html' : url.pathname;
    filePath = path.join(__dirname, 'public', filePath);

    // 安全检查：防止路径遍历
    if (!filePath.startsWith(path.join(__dirname, 'public'))) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
    }

    fs.readFile(filePath, (err, content) => {
        if (err) {
            res.writeHead(404);
            res.end('File not found');
            return;
        }
        const ext = path.extname(filePath);
        const mimeTypes = {
            '.html': 'text/html',
            '.js': 'text/javascript',
            '.css': 'text/css',
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.ico': 'image/x-icon'
        };
        res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
        res.end(content);
    });
});

// ============ WebSocket 服务器（手机端连接） ============
const wss = new WebSocketServer({ server, path: '/monitor' });

wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log(`\n📱 手机已连接: ${clientIp}`);
    connectedClients++;
    connectionStatus.phoneConnected = true;
    connectionStatus.connectedAt = new Date().toISOString();

    broadcastToSSEClients({
        type: 'connection_status',
        data: { ...connectionStatus, connectedClients }
    });

    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());

            switch (message.type) {
                case 'screen_frame':
                    handleScreenFrame(message);
                    break;
                case 'chat_message':
                    handleChatMessage(message);
                    break;
                case 'notification':
                    handleNotification(message);
                    break;
                case 'system_status':
                    handleSystemStatus(message);
                    break;
                default:
                    console.log(`  未知消息类型: ${message.type}`);
            }
        } catch (e) {
            console.error('  解析消息失败:', e.message);
        }
    });

    ws.on('close', () => {
        console.log(`📱 手机已断开: ${clientIp}`);
        connectedClients--;
        if (connectedClients < 0) connectedClients = 0;
        connectionStatus.phoneConnected = connectedClients > 0;
        broadcastToSSEClients({
            type: 'connection_status',
            data: { ...connectionStatus, connectedClients }
        });
    });

    ws.on('error', (err) => {
        console.error(`  WebSocket 错误: ${err.message}`);
    });
});

// ============ 数据处理函数 ============

/**
 * 处理屏幕帧数据
 */
function handleScreenFrame(frame) {
    latestScreenFrame = {
        frameNumber: frame.frameNumber,
        imageDataBase64: frame.imageDataBase64,
        width: frame.width,
        height: frame.height,
        timestamp: frame.timestamp || Date.now()
    };

    screenFrames.push(latestScreenFrame);
    if (screenFrames.length > MAX_SCREEN_FRAMES) {
        screenFrames.shift();
    }

    // 广播到浏览器
    broadcastToSSEClients({
        type: 'screen_frame',
        data: {
            frameNumber: latestScreenFrame.frameNumber,
            width: latestScreenFrame.width,
            height: latestScreenFrame.height,
            imageDataBase64: latestScreenFrame.imageDataBase64,
            timestamp: latestScreenFrame.timestamp
        }
    });
}

/**
 * 处理聊天记录数据
 */
function handleChatMessage(data) {
    const entry = {
        appName: data.appName,
        packageName: data.packageName,
        screenText: data.screenText,
        messages: data.messages || [],
        timestamp: data.timestamp || Date.now(),
        receivedAt: new Date().toISOString()
    };

    chatMessages.push(entry);
    if (chatMessages.length > MAX_CHAT_MESSAGES) {
        chatMessages.shift();
    }

    console.log(`  💬 聊天记录: ${data.appName} (${data.messages?.length || 0} 条消息)`);

    broadcastToSSEClients({
        type: 'chat_message',
        data: entry
    });
}

/**
 * 处理通知数据
 */
function handleNotification(data) {
    const entry = {
        packageName: data.packageName,
        appName: data.appName,
        title: data.title,
        text: data.text,
        timestamp: data.timestamp || Date.now(),
        receivedAt: new Date().toISOString()
    };

    notifications.push(entry);
    if (notifications.length > MAX_NOTIFICATIONS) {
        notifications.shift();
    }

    console.log(`  🔔 通知: [${data.appName}] ${data.title}: ${data.text?.substring(0, 50)}`);

    broadcastToSSEClients({
        type: 'notification',
        data: entry
    });
}

/**
 * 处理系统状态
 */
function handleSystemStatus(data) {
    connectionStatus.screenCaptureEnabled = data.screenCaptureEnabled || false;
    connectionStatus.chatMonitorEnabled = data.chatMonitorEnabled || false;
    connectionStatus.notificationMonitorEnabled = data.notificationMonitorEnabled || false;

    console.log(`  状态: 屏幕=${connectionStatus.screenCaptureEnabled} 聊天=${connectionStatus.chatMonitorEnabled} 通知=${connectionStatus.notificationMonitorEnabled}`);

    broadcastToSSEClients({
        type: 'system_status',
        data: { ...connectionStatus, connectedClients }
    });
}

/**
 * 发送命令到手机
 */
function sendCommandToPhone(command) {
    const json = JSON.stringify(command);
    let sent = false;

    wss.clients.forEach(client => {
        if (client.readyState === 1) { // WebSocket.OPEN
            client.send(json);
            sent = true;
        }
    });

    console.log(`  发送命令: ${command.action} (${sent ? '已发送' : '无手机连接'})`);
}

// ============ SSE (Server-Sent Events) 推送 ============

/**
 * 处理 SSE 连接
 */
function handleSSE(req, res) {
    res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*'
    });

    // 发送初始连接信息
    res.write(`data: ${JSON.stringify({
        type: 'connected',
        data: { ...connectionStatus, connectedClients, frameCount: screenFrames.length }
    })}\n\n`);

    // 如果有最新的屏幕帧，立即发送
    if (latestScreenFrame) {
        res.write(`data: ${JSON.stringify({
            type: 'screen_frame',
            data: {
                frameNumber: latestScreenFrame.frameNumber,
                width: latestScreenFrame.width,
                height: latestScreenFrame.height,
                imageDataBase64: latestScreenFrame.imageDataBase64,
                timestamp: latestScreenFrame.timestamp
            }
        })}\n\n`);
    }

    sseClients.add(res);
    console.log(`  🌐 浏览器已连接 (共 ${sseClients.size} 个)`);

    // 心跳保持连接
    const heartbeat = setInterval(() => {
        res.write(`: heartbeat\n\n`);
    }, 15000);

    req.on('close', () => {
        sseClients.delete(res);
        clearInterval(heartbeat);
        console.log(`  🌐 浏览器断开 (剩余 ${sseClients.size} 个)`);
    });
}

/**
 * 广播数据到所有 SSE 客户端
 */
function broadcastToSSEClients(data) {
    const json = `data: ${JSON.stringify(data)}\n\n`;
    sseClients.forEach(client => {
        try {
            client.write(json);
        } catch (e) {
            sseClients.delete(client);
        }
    });
}

// ============ 启动服务器 ============
server.listen(PORT, () => {
    console.log(`\n╔══════════════════════════════════════════════╗`);
    console.log(`║        📱 手机监控桌面端查看器              ║`);
    console.log(`╠══════════════════════════════════════════════╣`);
    console.log(`║  网页查看器: http://localhost:${PORT}          ║`);
    console.log(`║  WS 地址:    ws://本机IP:${PORT}/monitor      ║`);
    console.log(`║                                              ║`);
    console.log(`║  手机端设置:                                 ║`);
    console.log(`║  ws://电脑IP:${PORT}/monitor                   ║`);
    console.log(`╚══════════════════════════════════════════════╝`);
    console.log(`\n等待手机连接...\n`);
});
