package ai.openclaw.channels;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 飞书 WebSocket 客户端。
 * <p>
 * 实现飞书长连接协议，包括：
 * <ul>
 *   <li>WebSocket 连接建立</li>
 *   <li>消息接收与分发</li>
 *   <li>心跳保持</li>
 *   <li>自动重连</li>
 * </ul>
 * </p>
 */
public class FeishuWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebSocketClient.class);

    private final String wsUrl;
    private final Consumer<String> messageHandler;
    private final Runnable onConnect;
    private final Runnable onDisconnect;
    private final ObjectMapper mapper;

    private java.net.http.WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    // 消息序列号
    private long sequence = 0;

    /**
     * 创建 WebSocket 客户端。
     *
     * @param wsUrl          WebSocket 连接地址
     * @param messageHandler 消息处理器
     * @param onConnect      连接成功回调
     * @param onDisconnect   断开连接回调
     */
    public FeishuWebSocketClient(String wsUrl, Consumer<String> messageHandler,
                                  Runnable onConnect, Runnable onDisconnect) {
        this.wsUrl = wsUrl;
        this.messageHandler = messageHandler;
        this.onConnect = onConnect;
        this.onDisconnect = onDisconnect;
        this.mapper = new ObjectMapper();
    }

    /**
     * 建立 WebSocket 连接。
     */
    public void connect() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

            client.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new java.net.http.WebSocket.Listener() {
                        private final StringBuilder messageBuilder = new StringBuilder();

                        @Override
                        public void onOpen(java.net.http.WebSocket webSocket) {
                            log.info("Feishu WebSocket connection opened");
                            FeishuWebSocketClient.this.webSocket = webSocket;
                            connected.set(true);
                            webSocket.request(1);
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket webSocket,
                                                                               CharSequence data, boolean last) {
                            messageBuilder.append(data);
                            if (last) {
                                String message = messageBuilder.toString();
                                messageBuilder.setLength(0);
                                try {
                                    messageHandler.accept(message);
                                } catch (Exception e) {
                                    log.error("Error handling WebSocket message", e);
                                }
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket webSocket,
                                                                                int statusCode, String reason) {
                            log.info("Feishu WebSocket closed: {} - {}", statusCode, reason);
                            connected.set(false);
                            if (!closing.get()) {
                                onDisconnect.run();
                            }
                            return null;
                        }

                        @Override
                        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                            log.error("Feishu WebSocket error", error);
                            connected.set(false);
                            if (!closing.get()) {
                                onDisconnect.run();
                            }
                        }
                    })
                    .thenAccept(ws -> {
                        // 连接成功后通知
                        if (connected.get()) {
                            onConnect.run();
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Failed to connect to Feishu WebSocket", e);
                        connected.set(false);
                        onDisconnect.run();
                        return null;
                    });

        } catch (Exception e) {
            log.error("Error creating Feishu WebSocket connection", e);
            connected.set(false);
            onDisconnect.run();
        }
    }

    /**
     * 发送 Ping 心跳。
     */
    public void sendPing() {
        if (webSocket == null || !connected.get()) {
            return;
        }

        try {
            // 飞书心跳格式：{"type":"ping","sequence":123}
            sequence++;
            Map<String, Object> ping = Map.of("type", "ping", "sequence", sequence);
            String pingJson = mapper.writeValueAsString(ping);

            webSocket.sendText(pingJson, true);
            log.debug("Sent ping to Feishu: sequence={}", sequence);
        } catch (Exception e) {
            log.error("Error sending ping to Feishu", e);
        }
    }

    /**
     * 发送文本消息。
     *
     * @param message 消息内容
     */
    public void sendText(String message) {
        if (webSocket == null || !connected.get()) {
            log.warn("Cannot send message: WebSocket not connected");
            return;
        }

        webSocket.sendText(message, true);
    }

    /**
     * 关闭连接。
     */
    public void close() {
        closing.set(true);
        connected.set(false);

        if (webSocket != null) {
            try {
                webSocket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Client closing");
            } catch (Exception e) {
                log.debug("Error closing WebSocket", e);
            }
            webSocket = null;
        }
    }

    /**
     * 检查是否已连接。
     */
    public boolean isConnected() {
        return connected.get();
    }
}