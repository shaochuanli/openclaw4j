package ai.openclaw.channels;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 飞书通道实现类。
 * <p>
 * 支持两种连接模式：
 * <ul>
 *   <li><b>Webhook 模式</b>：飞书主动向服务器发送 HTTP 请求，需要公网地址</li>
 *   <li><b>WebSocket 模式</b>：客户端主动连接飞书，适合内网环境</li>
 * </ul>
 * </p>
 */
public class FeishuChannel extends Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    private final ConfigManager configManager;
    private final AgentManager agentManager;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    // Access Token 缓存
    private String cachedAccessToken;
    private long tokenExpireTime;

    // WebSocket 相关
    private FeishuWebSocketClient wsClient;
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> reconnectFuture;

    // 状态
    private String statusMessage = "offline";
    private String connectionMode = "websocket";

    /**
     * 构造飞书通道实例。
     *
     * @param configManager 配置管理器
     * @param agentManager  智能体管理器
     */
    public FeishuChannel(ConfigManager configManager, AgentManager agentManager) {
        super("feishu", "飞书");
        this.configManager = configManager;
        this.agentManager = agentManager;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feishu-ws");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动飞书通道。
     *
     * @throws Exception 启动过程中发生异常
     */
    @Override
    public void start() throws Exception {
        OpenClaw4jConfig.FeishuConfig cfg = configManager.getConfig().channels.feishu;
        if (cfg == null || !cfg.enabled) {
            statusMessage = "disabled";
            return;
        }

        String appId = configManager.resolveSecret(cfg.appId);
        String appSecret = configManager.resolveSecret(cfg.appSecret);

        if (appId == null || appSecret == null ||
            appId.startsWith("${") || appSecret.startsWith("${")) {
            statusMessage = "error: appId or appSecret not configured";
            log.warn("Feishu appId or appSecret not configured");
            return;
        }

        try {
            // 验证配置：尝试获取 access token
            String token = getTenantAccessToken();
            if (token == null) {
                statusMessage = "error: failed to get access token";
                log.error("Failed to get Feishu access token");
                return;
            }

            connectionMode = cfg.connectionMode != null ? cfg.connectionMode : "websocket";

            if ("websocket".equals(connectionMode)) {
                // WebSocket 长连接模式
                startWebSocketMode();
            } else {
                // Webhook 模式
                statusMessage = "online";
                log.info("Feishu channel started (webhook mode, waiting for events at {})",
                         cfg.webhookPath != null ? cfg.webhookPath : "/feishu/events");
            }

            running = true;
        } catch (Exception e) {
            statusMessage = "error: " + e.getMessage();
            log.error("Failed to start Feishu channel", e);
            throw e;
        }
    }

    // ─── WebSocket 模式 ─────────────────────────────────────────────────────

    /**
     * 启动 WebSocket 长连接模式。
     */
    private void startWebSocketMode() throws Exception {
        log.info("Starting Feishu WebSocket mode...");

        // 获取 WebSocket 连接地址
        String wsUrl = getWebSocketUrl();
        if (wsUrl == null) {
            throw new RuntimeException("Failed to get WebSocket URL");
        }

        // 创建并启动 WebSocket 客户端
        wsClient = new FeishuWebSocketClient(
            wsUrl,
            this::handleWebSocketMessage,
            this::onWebSocketConnect,
            this::onWebSocketDisconnect
        );
        wsClient.connect();

        statusMessage = "connecting";
        log.info("Feishu WebSocket connecting to: {}", wsUrl.substring(0, Math.min(50, wsUrl.length())) + "...");
    }

    /**
     * 获取 WebSocket 连接地址。
     */
    private String getWebSocketUrl() throws Exception {
        OpenClaw4jConfig.FeishuConfig cfg = configManager.getConfig().channels.feishu;
        String accessToken = getTenantAccessToken();

        String baseUrl = "lark".equals(cfg.domain)
            ? "https://open.larksuite.com"
            : "https://open.feishu.cn";

        String url = baseUrl + "/open-apis/v3/ws/start";

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("app_id", configManager.resolveSecret(cfg.appId));
        reqBody.put("app_secret", configManager.resolveSecret(cfg.appSecret));

        String jsonBody = mapper.writeValueAsString(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = mapper.readValue(response.body(), Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code != null && code == 0) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) respMap.get("data");
            if (data != null) {
                return (String) data.get("url");
            }
        }

        log.error("Failed to get WebSocket URL: {}", respMap.get("msg"));
        return null;
    }

    /**
     * WebSocket 连接成功回调。
     */
    private void onWebSocketConnect() {
        wsConnected.set(true);
        statusMessage = "online";
        log.info("Feishu WebSocket connected");

        // 启动心跳
        startHeartbeat();

        // 取消重连任务（如果有的话）
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    /**
     * WebSocket 断开连接回调。
     */
    private void onWebSocketDisconnect() {
        wsConnected.set(false);
        statusMessage = "disconnected";
        log.warn("Feishu WebSocket disconnected");

        // 停止心跳
        stopHeartbeat();

        // 尝试重连
        scheduleReconnect();
    }

    /**
     * 启动心跳。
     */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (wsClient != null && wsConnected.get()) {
                wsClient.sendPing();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 停止心跳。
     */
    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    /**
     * 调度重连。
     */
    private void scheduleReconnect() {
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return; // 已有重连任务
        }

        log.info("Scheduling Feishu WebSocket reconnect in 5 seconds...");
        statusMessage = "reconnecting";

        reconnectFuture = scheduler.schedule(() -> {
            try {
                log.info("Attempting Feishu WebSocket reconnect...");
                startWebSocketMode();
            } catch (Exception e) {
                log.error("Feishu WebSocket reconnect failed", e);
                scheduleReconnect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * 处理 WebSocket 消息。
     */
    private void handleWebSocketMessage(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msgMap = mapper.readValue(message, Map.class);
            String type = (String) msgMap.get("type");

            if ("pong".equals(type)) {
                // 心跳响应，忽略
                log.debug("Received pong from Feishu");
                return;
            }

            if ("event".equals(type)) {
                // 事件消息
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) msgMap.get("header");
                if (header != null) {
                    String eventType = (String) header.get("event_type");
                    log.debug("Received Feishu event via WebSocket: {}", eventType);

                    if ("im.message.receive_v1".equals(eventType)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = (Map<String, Object>) msgMap.get("event");
                        if (event != null) {
                            handleMessageEventFromMap(event);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
        }
    }

    /**
     * 处理从 WebSocket 接收的消息事件。
     */
    @SuppressWarnings("unchecked")
    private void handleMessageEventFromMap(Map<String, Object> event) throws Exception {
        OpenClaw4jConfig.FeishuConfig cfg = configManager.getConfig().channels.feishu;

        Map<String, Object> message = (Map<String, Object>) event.get("message");
        Map<String, Object> sender = (Map<String, Object>) event.get("sender");

        if (message == null || sender == null) return;

        String messageId = (String) message.get("message_id");
        String chatId = (String) message.get("chat_id");
        String messageType = (String) message.get("message_type");
        String content = (String) message.get("content");

        Map<String, Object> senderId = (Map<String, Object>) sender.get("sender_id");
        String senderOpenId = senderId != null ? (String) senderId.get("open_id") : null;
        String senderType = (String) sender.get("sender_type");

        String text = parseMessageContent(content, messageType);
        if (text == null || text.isEmpty()) {
            return;
        }

        if (!checkPermission(cfg, chatId, senderOpenId, senderType)) {
            log.debug("Message from unauthorized sender: {}", senderOpenId);
            return;
        }

        if (text.startsWith("/")) {
            handleCommand(messageId, chatId, text);
            return;
        }

        String sessionKey = "feishu:" + chatId;

        agentManager.runAsync("default", sessionKey, text, new AgentManager.AgentCallback() {
            @Override
            public void onChunk(String chunk) {}

            @Override
            public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                try {
                    sendMessage(chatId, fullResponse);
                } catch (Exception e) {
                    log.error("Failed to send Feishu reply", e);
                }
            }

            @Override
            public void onError(String error) {
                try {
                    sendMessage(chatId, "❌ 错误: " + error);
                } catch (Exception e) {
                    log.error("Failed to send error reply", e);
                }
            }
        });
    }

    // ─── Webhook 模式 ─────────────────────────────────────────────────────

    /**
     * 处理接收到的飞书事件（由Webhook Servlet调用）。
     *
     * @param eventJson 事件JSON字符串
     * @return 响应内容（用于URL验证）
     */
    public String handleWebhookEvent(String eventJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = mapper.readValue(eventJson, Map.class);

            // 处理URL验证挑战
            if (eventMap.containsKey("challenge")) {
                String challenge = (String) eventMap.get("challenge");
                Map<String, String> response = new HashMap<>();
                response.put("challenge", challenge);
                return mapper.writeValueAsString(response);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> header = (Map<String, Object>) eventMap.get("header");
            if (header == null) {
                return "{}";
            }

            String eventType = (String) header.get("event_type");
            log.debug("Received Feishu event via Webhook: {}", eventType);

            if ("im.message.receive_v1".equals(eventType)) {
                handleMessageEvent(eventMap);
            }

            return "{}";
        } catch (Exception e) {
            log.error("Error handling Feishu webhook event", e);
            return "{}";
        }
    }

    /**
     * 处理消息接收事件（Webhook 模式）。
     */
    @SuppressWarnings("unchecked")
    private void handleMessageEvent(Map<String, Object> eventMap) throws Exception {
        Map<String, Object> event = (Map<String, Object>) eventMap.get("event");
        if (event == null) return;
        handleMessageEventFromMap(event);
    }

    // ─── 消息处理 ─────────────────────────────────────────────────────

    /**
     * 解析消息内容。
     */
    @SuppressWarnings("unchecked")
    private String parseMessageContent(String content, String messageType) {
        if (content == null) return null;

        try {
            if ("text".equals(messageType)) {
                Map<String, Object> contentMap = mapper.readValue(content, Map.class);
                return (String) contentMap.get("content");
            } else if ("post".equals(messageType)) {
                return extractTextFromPost(content);
            } else {
                log.debug("Unsupported message type: {}", messageType);
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to parse message content", e);
            return null;
        }
    }

    /**
     * 从富文本消息中提取纯文本。
     */
    @SuppressWarnings("unchecked")
    private String extractTextFromPost(String content) {
        try {
            Map<String, Object> contentMap = mapper.readValue(content, Map.class);
            StringBuilder text = new StringBuilder();

            for (Object localeValue : contentMap.values()) {
                if (localeValue instanceof Map) {
                    Map<String, Object> localeContent = (Map<String, Object>) localeValue;
                    Object contentObj = localeContent.get("content");
                    if (contentObj instanceof List) {
                        List<?> contentList = (List<?>) contentObj;
                        for (Object paragraphObj : contentList) {
                            if (paragraphObj instanceof List) {
                                List<?> paragraph = (List<?>) paragraphObj;
                                for (Object elementObj : paragraph) {
                                    if (elementObj instanceof Map) {
                                        Map<String, Object> element = (Map<String, Object>) elementObj;
                                        if ("text".equals(element.get("tag"))) {
                                            text.append(element.get("text"));
                                        }
                                    }
                                }
                            }
                            text.append("\n");
                        }
                    }
                }
            }
            return text.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to extract text from post message", e);
            return null;
        }
    }

    /**
     * 检查发送者权限。
     */
    private boolean checkPermission(OpenClaw4jConfig.FeishuConfig cfg, String chatId, String senderId, String senderType) {
        if (chatId == null) return false;

        if (chatId.startsWith("oc_")) {
            if ("closed".equals(cfg.groupPolicy)) return false;
            if ("allowlist".equals(cfg.groupPolicy) && !cfg.groupAllowFrom.isEmpty()) {
                return cfg.groupAllowFrom.contains(chatId);
            }
            return true;
        }

        if ("closed".equals(cfg.dmPolicy)) return false;
        if ("open".equals(cfg.dmPolicy)) return true;
        if (!cfg.allowFrom.isEmpty()) {
            return cfg.allowFrom.contains(senderId);
        }
        return true;
    }

    /**
     * 处理命令消息。
     */
    private void handleCommand(String messageId, String chatId, String content) throws Exception {
        String command = content.trim().toLowerCase();

        if ("/start".equals(command) || "/help".equals(command)) {
            sendMessage(chatId, """
                🦞 *OpenClaw4j* — 您的AI助手

                可用命令：
                • /new 或 /reset — 开始新对话
                • /help — 显示帮助信息

                直接发送消息即可开始对话！
                """);
        } else if ("/new".equals(command) || "/reset".equals(command)) {
            String sessionKey = "feishu:" + chatId;
            agentManager.getSessionManager().resetSession(sessionKey);
            sendMessage(chatId, "✅ 已开始新对话");
        }
    }

    /**
     * 发送消息到飞书。
     */
    private void sendMessage(String chatId, String text) throws Exception {
        OpenClaw4jConfig.FeishuConfig cfg = configManager.getConfig().channels.feishu;
        String accessToken = getTenantAccessToken();

        if (accessToken == null) {
            log.error("Failed to send message: no access token");
            return;
        }

        String content = buildPostContent(text);
        String baseUrl = "lark".equals(cfg.domain)
            ? "https://open.larksuite.com"
            : "https://open.feishu.cn";

        String url = baseUrl + "/open-apis/im/v1/messages?receive_id_type=chat_id";

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("receive_id", chatId);
        reqBody.put("msg_type", "post");
        reqBody.put("content", content);

        String jsonBody = mapper.writeValueAsString(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = mapper.readValue(response.body(), Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code != null && code != 0) {
            log.error("Failed to send Feishu message: code={}, msg={}", code, respMap.get("msg"));
        }
    }

    /**
     * 构建富文本消息内容。
     */
    private String buildPostContent(String text) {
        try {
            Map<String, Object> postContent = new LinkedHashMap<>();
            Map<String, Object> zhContent = new LinkedHashMap<>();
            zhContent.put("title", "");

            List<List<Map<String, Object>>> contentList = new ArrayList<>();
            List<Map<String, Object>> paragraph = new ArrayList<>();
            Map<String, Object> textElement = new LinkedHashMap<>();
            textElement.put("tag", "text");
            textElement.put("text", text);
            paragraph.add(textElement);
            contentList.add(paragraph);

            zhContent.put("content", contentList);
            postContent.put("zh_cn", zhContent);

            return mapper.writeValueAsString(postContent);
        } catch (Exception e) {
            log.error("Error building post content", e);
            return "{\"zh_cn\":{\"title\":\"\",\"content\":[[{\"tag\":\"text\",\"text\":\"" +
                   text.replace("\"", "\\\"") + "\"}]]}}";
        }
    }

    // ─── Token 管理 ─────────────────────────────────────────────────────

    /**
     * 获取租户访问令牌。
     */
    private String getTenantAccessToken() throws Exception {
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return cachedAccessToken;
        }

        OpenClaw4jConfig.FeishuConfig cfg = configManager.getConfig().channels.feishu;
        String baseUrl = "lark".equals(cfg.domain)
            ? "https://open.larksuite.com"
            : "https://open.feishu.cn";

        String url = baseUrl + "/open-apis/auth/v3/tenant_access_token/internal";

        Map<String, String> reqBody = new HashMap<>();
        reqBody.put("app_id", configManager.resolveSecret(cfg.appId));
        reqBody.put("app_secret", configManager.resolveSecret(cfg.appSecret));

        String jsonBody = mapper.writeValueAsString(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = mapper.readValue(response.body(), Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code != null && code == 0) {
            cachedAccessToken = (String) respMap.get("tenant_access_token");
            Integer expire = (Integer) respMap.get("expire");
            tokenExpireTime = System.currentTimeMillis() + (expire != null ? (expire - 60) * 1000L : 7000 * 1000L);
            return cachedAccessToken;
        }

        log.error("Failed to get tenant access token: {}", respMap.get("msg"));
        return null;
    }

    // ─── 生命周期 ─────────────────────────────────────────────────────

    /**
     * 停止飞书通道。
     */
    @Override
    public void stop() {
        // 停止 WebSocket
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }

        stopHeartbeat();

        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }

        scheduler.shutdownNow();

        running = false;
        wsConnected.set(false);
        cachedAccessToken = null;
        statusMessage = "offline";
        log.info("Feishu channel stopped");
    }

    /**
     * 获取通道当前状态。
     */
    @Override
    public String getStatus() {
        return statusMessage;
    }

    /**
     * 获取连接模式。
     */
    public String getConnectionMode() {
        return connectionMode;
    }

    /**
     * 是否已连接（WebSocket 模式）。
     */
    public boolean isConnected() {
        return wsConnected.get();
    }

    /**
     * 获取配置。
     */
    public OpenClaw4jConfig.FeishuConfig getConfig() {
        return configManager.getConfig().channels.feishu;
    }
}