package ai.openclaw.gateway;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import ai.openclaw.skills.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenClaw4j 网关的 WebSocket 处理器。
 * 负责 RPC 方法分发和向已连接客户端推送事件。
 */
@WebSocket
public class GatewayWebSocket {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocket.class);

    private final ObjectMapper mapper;
    private final ConfigManager configManager;
    private final AgentManager agentManager;
    private final GatewaySessionRegistry sessionRegistry;
    private final AtomicLong seqCounter = new AtomicLong(0);

    /** 每个 WebSocket 会话共享的异步技能操作线程池（安装/更新） */
    private final ExecutorService skillsExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skills-ws-worker");
        t.setDaemon(true);
        return t;
    });

    private Session wsSession;
    private boolean authenticated = false;
    private String clientId;
    private String clientName;

    /**
     * 构造 GatewayWebSocket 实例。
     *
     * @param mapper          JSON 序列化工具
     * @param configManager   配置管理器
     * @param agentManager    Agent 管理器
     * @param sessionRegistry WebSocket 会话注册中心
     */
    public GatewayWebSocket(ObjectMapper mapper, ConfigManager configManager,
                            AgentManager agentManager, GatewaySessionRegistry sessionRegistry) {
        this.mapper = mapper;
        this.configManager = configManager;
        this.agentManager = agentManager;
        this.sessionRegistry = sessionRegistry;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.wsSession = session;
        this.clientId = UUID.randomUUID().toString();
        log.info("WebSocket connected: {}", clientId);

        // 发送认证挑战
        OpenClaw4jConfig.AuthConfig auth = configManager.getConfig().gateway.auth;
        GatewayProtocol.ConnectChallenge challenge = new GatewayProtocol.ConnectChallenge(
                UUID.randomUUID().toString(),
                auth.mode
        );
        sendEvent("connect.challenge", challenge);

        // 若认证模式为 "none"，则自动认证
        if ("none".equals(auth.mode)) {
            authenticated = true;
            sessionRegistry.register(clientId, this);
            sendEvent("presence", Map.of("status", "online"));
        }
    }

    /**
     * WebSocket 连接关闭时的回调。
     *
     * @param statusCode 关闭状态码
     * @param reason     关闭原因
     */
    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        log.info("WebSocket closed: {} ({})", clientId, statusCode);
        sessionRegistry.unregister(clientId);
    }

    /**
     * WebSocket 发生错误时的回调。
     *
     * @param error 错误对象
     */
    @OnWebSocketError
    public void onError(Throwable error) {
        log.error("WebSocket error for {}: {}", clientId, error.getMessage());
    }

    /**
     * 接收到 WebSocket 消息时的回调。
     * 解析请求帧并根据 method 分发到对应的处理方法。
     *
     * @param message 接收到的文本消息（JSON 格式）
     */
    @OnWebSocketMessage
    public void onMessage(String message) {
        try {
            JsonNode node = mapper.readTree(message);
            GatewayProtocol.RequestFrame req = mapper.treeToValue(node, GatewayProtocol.RequestFrame.class);

            if (req.method == null) {
                sendError(req.id, "INVALID_REQUEST", "Missing method");
                return;
            }

            // 认证检查：未认证时仅允许 auth 方法
            if (!authenticated && !"auth".equals(req.method)) {
                sendError(req.id, "NOT_AUTHENTICATED", "Please authenticate first");
                return;
            }

            dispatch(req, node.get("params"));
        } catch (Exception e) {
            log.error("Failed to handle message", e);
            sendError(null, "INTERNAL_ERROR", e.getMessage());
        }
    }

    /**
     * 根据 RPC 方法名分发请求到对应的处理方法。
     *
     * @param req    请求帧
     * @param params 请求参数（JSON 节点）
     * @throws Exception 处理过程中发生的异常
     */
    private void dispatch(GatewayProtocol.RequestFrame req, JsonNode params) throws Exception {
        switch (req.method) {
            case "auth" -> handleAuth(req, params);
            case "health" -> sendResponse(req.id, Map.of(
                "status", "ok",
                "version", ai.openclaw.OpenClaw4j.VERSION,
                "uptime", System.currentTimeMillis()
            ));
            case "config.get" -> handleConfigGet(req);
            case "config.set" -> handleConfigSet(req, params);
            case "config.patch" -> handleConfigPatch(req, params);
            case "sessions.list" -> handleSessionsList(req);
            case "sessions.reset" -> handleSessionReset(req, params);
            case "sessions.delete" -> handleSessionDelete(req, params);
            case "sessions.preview" -> handleSessionPreview(req, params);
            case "chat.send" -> handleChatSend(req, params);
            case "chat.history" -> handleChatHistory(req, params);
            case "chat.abort" -> handleChatAbort(req, params);
            case "agents.list" -> handleAgentsList(req);
            case "models.list" -> handleModelsList(req);
            case "channels.status" -> handleChannelsStatus(req);
            case "cron.list" -> handleCronList(req);
            case "cron.add" -> handleCronAdd(req, params);
            case "cron.remove" -> handleCronRemove(req, params);
            case "cron.run" -> handleCronRun(req, params);
            case "usage.status" -> handleUsageStatus(req);
            case "logs.tail" -> handleLogsTail(req, params);
            case "skills.list"          -> handleSkillsList(req, params);
            case "skills.store.search"  -> handleSkillsStoreSearch(req, params);
            case "skills.store.featured"-> handleSkillsStoreFeatured(req);
            case "skills.store.categories" -> handleSkillsStoreCategories(req);
            case "skills.install"       -> handleSkillsInstall(req, params);
            case "skills.uninstall"     -> handleSkillsUninstall(req, params);
            case "skills.update"        -> handleSkillsUpdate(req, params);
            case "skills.reload"        -> handleSkillsReload(req);
            case "skills.get"           -> handleSkillsGet(req, params);
            case "skills.enable"        -> handleSkillsEnable(req, params);
            case "skills.disable"       -> handleSkillsDisable(req, params);
            case "skills.scan"          -> handleSkillsScan(req, params);
            default -> sendError(req.id, "METHOD_NOT_FOUND", "Unknown method: " + req.method);
        }
    }

    // ─── 认证处理 ────────────────────────────────────────────────────────────────

    /**
     * 处理 auth 认证请求。
     * 支持 token、password 和 none 三种认证模式。
     *
     * @param req    请求帧
     * @param params 认证参数（token 或 password）
     */
    private void handleAuth(GatewayProtocol.RequestFrame req, JsonNode params) {
        OpenClaw4jConfig.AuthConfig authConfig = configManager.getConfig().gateway.auth;

        boolean ok = false;
        if ("none".equals(authConfig.mode)) {
            ok = true;
        } else if ("token".equals(authConfig.mode)) {
            String token = params != null && params.has("token") ? params.get("token").asText() : null;
            String resolved = configManager.resolveSecret(authConfig.token);
            ok = resolved != null && resolved.equals(token);
        } else if ("password".equals(authConfig.mode)) {
            String password = params != null && params.has("password") ? params.get("password").asText() : null;
            String resolved = configManager.resolveSecret(authConfig.password);
            ok = resolved != null && resolved.equals(password);
        }

        if (ok) {
            authenticated = true;
            if (params != null) {
                if (params.has("clientId")) clientId = params.get("clientId").asText();
                if (params.has("clientName")) clientName = params.get("clientName").asText();
            }
            sessionRegistry.register(clientId, this);
            log.info("Client authenticated: {} ({})", clientId, clientName);
            sendResponse(req.id, Map.of("ok", true, "clientId", clientId));
            sendEvent("presence", Map.of("status", "online"));
        } else {
            sendError(req.id, "AUTH_FAILED", "Invalid credentials");
        }
    }

    // ─── 配置管理 ────────────────────────────────────────────────────────────────

    /**
     * 获取当前完整配置。
     *
     * @param req 请求帧
     */
    private void handleConfigGet(GatewayProtocol.RequestFrame req) {
        sendResponse(req.id, mapper.convertValue(configManager.getConfig(), ObjectNode.class));
    }

    /**
     * 完整替换配置。
     *
     * @param req    请求帧
     * @param params 配置内容
     * @throws IOException IO 异常
     */
    private void handleConfigSet(GatewayProtocol.RequestFrame req, JsonNode params) throws IOException {
        if (params == null) { sendError(req.id, "INVALID_PARAMS", "Missing params"); return; }
        configManager.patch(params.toString());
        sendResponse(req.id, Map.of("ok", true));
    }

    /**
     * 部分更新配置。
     *
     * @param req    请求帧
     * @param params 配置更新内容
     * @throws IOException IO 异常
     */
    private void handleConfigPatch(GatewayProtocol.RequestFrame req, JsonNode params) throws IOException {
        if (params == null) { sendError(req.id, "INVALID_PARAMS", "Missing params"); return; }
        configManager.patch(params.toString());
        sendResponse(req.id, Map.of("ok", true));
    }

    // ─── 会话管理 ────────────────────────────────────────────────────────────────

    /**
     * 列出所有会话。
     *
     * @param req 请求帧
     */
    private void handleSessionsList(GatewayProtocol.RequestFrame req) {
        sendResponse(req.id, agentManager.getSessionManager().listSessions());
    }

    /**
     * 重置指定会话。
     *
     * @param req    请求帧
     * @param params 参数（包含 sessionKey）
     */
    private void handleSessionReset(GatewayProtocol.RequestFrame req, JsonNode params) {
        String sessionKey = params != null && params.has("sessionKey") ? params.get("sessionKey").asText() : null;
        if (sessionKey == null) { sendError(req.id, "INVALID_PARAMS", "Missing sessionKey"); return; }
        agentManager.getSessionManager().resetSession(sessionKey);
        sendResponse(req.id, Map.of("ok", true));
    }

    /**
     * 删除指定会话。
     *
     * @param req    请求帧
     * @param params 参数（包含 sessionKey）
     */
    private void handleSessionDelete(GatewayProtocol.RequestFrame req, JsonNode params) {
        String sessionKey = params != null && params.has("sessionKey") ? params.get("sessionKey").asText() : null;
        if (sessionKey == null) { sendError(req.id, "INVALID_PARAMS", "Missing sessionKey"); return; }
        agentManager.getSessionManager().deleteSession(sessionKey);
        sendResponse(req.id, Map.of("ok", true));
    }

    /**
     * 获取会话预览信息。
     *
     * @param req    请求帧
     * @param params 参数（包含 sessionKey）
     */
    private void handleSessionPreview(GatewayProtocol.RequestFrame req, JsonNode params) {
        String sessionKey = params != null && params.has("sessionKey") ? params.get("sessionKey").asText() : null;
        if (sessionKey == null) { sendError(req.id, "INVALID_PARAMS", "Missing sessionKey"); return; }
        sendResponse(req.id, agentManager.getSessionManager().getSessionPreview(sessionKey));
    }

    // ─── 对话处理 ────────────────────────────────────────────────────────────────

    /**
     * 发送对话消息给 Agent。
     * 异步执行 Agent，通过事件推送流式输出和最终结果。
     *
     * @param req    请求帧
     * @param params 参数（agentId、sessionKey、text）
     */
    private void handleChatSend(GatewayProtocol.RequestFrame req, JsonNode params) {
        if (params == null) { sendError(req.id, "INVALID_PARAMS", "Missing params"); return; }

        String agentId = params.has("agentId") ? params.get("agentId").asText() : "default";
        String sessionKey = params.has("sessionKey") ? params.get("sessionKey").asText() : "webchat:default";
        String text = params.has("text") ? params.get("text").asText() : "";

        if (text.isBlank()) { sendError(req.id, "INVALID_PARAMS", "Empty message"); return; }

        // 确认请求已入队
        sendResponse(req.id, Map.of("ok", true, "queued", true));

        // 异步执行 Agent
        agentManager.runAsync(agentId, sessionKey, text, new AgentManager.AgentCallback() {
            @Override
            public void onChunk(String chunk) {
                sendEvent("chat", Map.of(
                    "type", "chunk",
                    "sessionKey", sessionKey,
                    "agentId", agentId,
                    "content", chunk
                ));
            }

            @Override
            public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                sendEvent("chat", Map.of(
                    "type", "complete",
                    "sessionKey", sessionKey,
                    "agentId", agentId,
                    "content", fullResponse,
                    "usage", Map.of(
                        "inputTokens", usage.inputTokens,
                        "outputTokens", usage.outputTokens
                    )
                ));
            }

            @Override
            public void onError(String error) {
                sendEvent("chat", Map.of(
                    "type", "error",
                    "sessionKey", sessionKey,
                    "agentId", agentId,
                    "error", error
                ));
            }

            @Override
            public void onToolCall(String toolName, String arguments) {
                sendEvent("chat", Map.of(
                    "type", "tool_call",
                    "sessionKey", sessionKey,
                    "agentId", agentId,
                    "toolName", toolName,
                    "arguments", arguments != null ? arguments : "{}"
                ));
            }

            @Override
            public void onToolResult(String toolName, String result, boolean success) {
                sendEvent("chat", Map.of(
                    "type", "tool_result",
                    "sessionKey", sessionKey,
                    "agentId", agentId,
                    "toolName", toolName,
                    "result", result != null ? (result.length() > 500 ? result.substring(0, 500) + "..." : result) : "",
                    "success", success
                ));
            }
        });
    }

    /**
     * 获取对话历史记录。
     *
     * @param req    请求帧
     * @param params 参数（sessionKey、limit）
     */
    private void handleChatHistory(GatewayProtocol.RequestFrame req, JsonNode params) {
        String sessionKey = params != null && params.has("sessionKey") ? params.get("sessionKey").asText() : "webchat:default";
        int limit = params != null && params.has("limit") ? params.get("limit").asInt(50) : 50;
        sendResponse(req.id, agentManager.getSessionManager().getChatHistory(sessionKey, limit));
    }

    /**
     * 中止正在进行的对话。
     *
     * @param req    请求帧
     * @param params 参数（sessionKey）
     */
    private void handleChatAbort(GatewayProtocol.RequestFrame req, JsonNode params) {
        String sessionKey = params != null && params.has("sessionKey") ? params.get("sessionKey").asText() : null;
        agentManager.abort(sessionKey);
        sendResponse(req.id, Map.of("ok", true));
    }

    // ─── Agent 管理 ────────────────────────────────────────────────────────────────

    /**
     * 列出所有配置的 Agent。
     *
     * @param req 请求帧
     */
    private void handleAgentsList(GatewayProtocol.RequestFrame req) {
        sendResponse(req.id, configManager.getConfig().agents.agents);
    }

    // ─── 模型管理 ────────────────────────────────────────────────────────────────

    /**
     * 列出所有可用的 AI 模型。
     *
     * @param req 请求帧
     */
    private void handleModelsList(GatewayProtocol.RequestFrame req) {
        List<Map<String, Object>> modelList = new ArrayList<>();
        configManager.getConfig().models.providers.forEach((providerId, provider) -> {
            for (var model : provider.models) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", providerId + "/" + model.id);
                entry.put("name", model.name);
                entry.put("providerId", providerId);
                entry.put("modelId", model.id);
                entry.put("api", provider.api);
                entry.put("input", model.input);
                entry.put("contextWindow", model.contextWindow);
                entry.put("maxTokens", model.maxTokens);
                modelList.add(entry);
            }
        });
        sendResponse(req.id, modelList);
    }

    // ─── 技能管理 ──────────────────────────────────────────────────────────────

    /**
     * skills.list - 列出所有已加载的技能（包括工具技能和知识技能）。
     *
     * 参数（可选）：
     *   source  : "all" | "bundled" | "managed" | "workspace" | "extra"
     *   enabled : 布尔值过滤器
     *
     * 响应：
     *   { tools: [...], groups: {...}, knowledgeSkills: [...], storeUrl: "..." }
     *
     * @param req    请求帧
     * @param params 过滤参数
     */
    private void handleSkillsList(GatewayProtocol.RequestFrame req, JsonNode params) {
        // 从 SkillRegistry 获取工具技能
        List<Map<String, Object>> toolSkills = new ArrayList<>();
        agentManager.getSkillRegistry().getAllTools().forEach((name, tool) -> {
            ai.openclaw.tools.ToolDefinition def = tool.getDefinition();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", def.name);
            entry.put("description", def.description);
            entry.put("type", "tool");
            toolSkills.add(entry);
        });

        // 从 SkillManager 获取知识技能
        List<Map<String, Object>> knowledgeSkills = new ArrayList<>();
        SkillManager skillManager = agentManager.getSkillManager();
        if (skillManager != null) {
            String sourceFilter = params != null && params.has("source") ? params.get("source").asText() : "all";
            Boolean enabledFilter = params != null && params.has("enabled") ? params.get("enabled").asBoolean() : null;

            skillManager.getAllSkills().stream()
                .filter(e -> "all".equals(sourceFilter) || e.source.name().equalsIgnoreCase(sourceFilter))
                .filter(e -> enabledFilter == null || e.enabled == enabledFilter)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key",         e.getKey());
                    m.put("name",        e.meta.name);
                    m.put("description", e.meta.description);
                    m.put("version",     e.meta.version);
                    m.put("author",      e.meta.author);
                    m.put("homepage",    e.meta.homepage);
                    m.put("emoji",       e.meta.emoji);
                    m.put("tags",        e.meta.tags);
                    m.put("os",          e.meta.os);
                    m.put("source",      e.source.name().toLowerCase());
                    m.put("enabled",     e.enabled);
                    m.put("installState",e.installState.name().toLowerCase());
                    m.put("path",        e.getDisplayPath());
                    m.put("updateAvailable", e.updateAvailable);
                    m.put("type",        "knowledge");
                    knowledgeSkills.add(m);
                });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolSkills);
        result.put("groups", agentManager.getSkillRegistry().getSkillGroups());
        result.put("knowledgeSkills", knowledgeSkills);
        result.put("storeUrl", skillManager != null ? skillManager.getStoreUrl()
                                                    : SkillHubClient.DEFAULT_STORE_URL);
        result.put("skillManagerEnabled", skillManager != null);
        if (skillManager != null) {
            result.put("countBySource", skillManager.getSkillCountBySource());
        }
        sendResponse(req.id, result);
    }

    /**
     * skills.get - 根据 key 获取单个知识技能的详情。
     *
     * @param req    请求帧
     * @param params 参数（key: "github"）
     */
    private void handleSkillsGet(GatewayProtocol.RequestFrame req, JsonNode params) {
        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) {
            sendError(req.id, "INVALID_PARAMS", "Missing key"); return;
        }
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }

        sm.getSkill(key).ifPresentOrElse(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",         e.getKey());
            m.put("name",        e.meta.name);
            m.put("description", e.meta.description);
            m.put("version",     e.meta.version);
            m.put("author",      e.meta.author);
            m.put("homepage",    e.meta.homepage);
            m.put("emoji",       e.meta.emoji);
            m.put("tags",        e.meta.tags);
            m.put("os",          e.meta.os);
            m.put("source",      e.source.name().toLowerCase());
            m.put("enabled",     e.enabled);
            m.put("installState",e.installState.name().toLowerCase());
            m.put("path",        e.getDisplayPath());
            m.put("body",        e.getBody());
            m.put("updateAvailable", e.updateAvailable);
            m.put("storeLatestVersion", e.storeLatestVersion);
            sendResponse(req.id, m);
        }, () -> sendError(req.id, "NOT_FOUND", "Skill not found: " + key));
    }

    /**
     * skills.store.search - 搜索 SkillHub 技能商店。
     *
     * @param req    请求帧
     * @param params 搜索参数（q、category、tag、page、pageSize）
     */
    private void handleSkillsStoreSearch(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }

        String query    = params != null && params.has("q")        ? params.get("q").asText()        : null;
        String category = params != null && params.has("category") ? params.get("category").asText() : null;
        String tag      = params != null && params.has("tag")      ? params.get("tag").asText()      : null;
        int page        = params != null && params.has("page")     ? params.get("page").asInt(1)     : 1;
        int pageSize    = params != null && params.has("pageSize") ? params.get("pageSize").asInt(20): 20;

        SkillHubClient.StoreSkillPage result = sm.searchStore(query, category, tag, page, pageSize);
        sendResponse(req.id, mapper.convertValue(result, com.fasterxml.jackson.databind.node.ObjectNode.class));
    }

    /**
     * skills.store.featured - 获取 SkillHub 商店精选技能。
     *
     * @param req 请求帧
     */
    private void handleSkillsStoreFeatured(GatewayProtocol.RequestFrame req) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        List<SkillHubClient.StoreSkill> featured = sm.getFeaturedSkills();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", featured);
        result.put("total", featured.size());
        sendResponse(req.id, result);
    }

    /**
     * skills.store.categories - 获取商店可用的分类列表。
     *
     * @param req 请求帧
     */
    private void handleSkillsStoreCategories(GatewayProtocol.RequestFrame req) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        sendResponse(req.id, sm.getStoreCategories());
    }

    /**
     * skills.install - 安装技能。
     *
     * 支持三种安装方式：
     * - { url: "https://..." } - 从 URL 下载 SKILL.md（服务端下载，避免 CORS）
     * - { id: "github" } - 从 SkillHub 商店按 ID 安装
     * - { skillMd: "---\nname:...\n---\n...", name: "x" } - 直接提供 SKILL.md 内容
     *
     * 该调用为异步：立即返回 { ok:true, installing:true }，
     * 完成后推送 "skills.installed" 或 "skills.install.error" 事件。
     *
     * @param req    请求帧
     * @param params 安装参数
     */
    private void handleSkillsInstall(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        if (params == null) { sendError(req.id, "INVALID_PARAMS", "Missing params"); return; }

        // URL 安装模式：服务端下载 SKILL.md（避免浏览器 CORS 限制）
        if (params.has("url")) {
            String skillUrl  = params.get("url").asText().trim();
            String nameHint  = params.has("name") ? params.get("name").asText() : null;
            sendResponse(req.id, java.util.Map.of("ok", true, "installing", true, "url", skillUrl));
            executor().submit(() -> {
                try {
                    // 通过 HttpURLConnection 下载内容（无需额外依赖）
                    java.net.URL httpUrl = new java.net.URI(skillUrl).toURL();
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) httpUrl.openConnection();
                    conn.setRequestProperty("User-Agent", "OpenClaw4j/1.0");
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(30_000);
                    conn.connect();
                    int status = conn.getResponseCode();
                    if (status < 200 || status >= 300) {
                        sendEvent("skills.install.error", java.util.Map.of(
                            "url", skillUrl,
                            "error", "HTTP " + status + " from " + skillUrl
                        ));
                        return;
                    }
                    String skillMd;
                    try (java.io.InputStream is = conn.getInputStream()) {
                        skillMd = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    if (skillMd == null || skillMd.isBlank()) {
                        sendEvent("skills.install.error", java.util.Map.of(
                            "url", skillUrl, "error", "Empty SKILL.md content"));
                        return;
                    }
                    // 从 nameHint、frontmatter 或 URL 路径推导技能名称
                    String skillName = nameHint;
                    if (skillName == null || skillName.isBlank()) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(?im)^name:\\s*[\"']?([^\"'\\r\\n]+)[\"']?")
                            .matcher(skillMd);
                        if (m.find()) {
                            skillName = m.group(1).trim();
                        } else {
                            String[] parts = skillUrl.split("/");
                            skillName = parts[parts.length - 1].replaceAll("(?i)\\.md$", "");
                            if (skillName.isBlank()) skillName = "skill";
                        }
                    }
                    SkillInstaller.InstallResult result = sm.installManual(skillName, skillMd);
                    if (result.success) {
                        sendEvent("skills.installed", java.util.Map.of(
                            "name", result.skillName,
                            "path", result.installDir.toString()
                        ));
                    } else {
                        sendEvent("skills.install.error", java.util.Map.of(
                            "url", skillUrl,
                            "error", result.error != null ? result.error : "unknown"
                        ));
                    }
                } catch (Exception ex) {
                    log.error("URL skill install failed: {}", skillUrl, ex);
                    sendEvent("skills.install.error", java.util.Map.of(
                        "url", skillUrl,
                        "error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
                    ));
                }
            });
            return;
        }

        // 手动 SKILL.md 安装模式 - 支持 "skillMd" 和 "skillMdContent" 两个键名
        String manualMdKey = params.has("skillMdContent") ? "skillMdContent"
                           : params.has("skillMd") ? "skillMd" : null;
        if (manualMdKey != null) {
            String skillMd   = params.get(manualMdKey).asText();
            String skillName = params.has("name") ? params.get("name").asText() : "custom-skill";
            sendResponse(req.id, Map.of("ok", true, "installing", true, "name", skillName));
            executor().submit(() -> {
                SkillInstaller.InstallResult result = sm.installManual(skillName, skillMd);
                if (result.success) {
                    sendEvent("skills.installed", Map.of(
                        "name", result.skillName,
                        "path", result.installDir.toString()
                    ));
                } else {
                    sendEvent("skills.install.error", Map.of(
                        "name", skillName,
                        "error", result.error != null ? result.error : "unknown"
                    ));
                }
            });
            return;
        }

        // 商店 ID 安装模式
        String storeId = params.has("id") ? params.get("id").asText() : null;
        if (storeId == null || storeId.isBlank()) {
            sendError(req.id, "INVALID_PARAMS", "Missing id or skillMd"); return;
        }
        sendResponse(req.id, Map.of("ok", true, "installing", true, "storeId", storeId));
        executor().submit(() -> {
            SkillInstaller.InstallResult result = sm.install(storeId);
            if (result.success) {
                sendEvent("skills.installed", Map.of(
                    "storeId", storeId,
                    "name", result.skillName,
                    "path", result.installDir.toString()
                ));
            } else {
                sendEvent("skills.install.error", Map.of(
                    "storeId", storeId,
                    "error", result.error != null ? result.error : "unknown"
                ));
            }
        });
    }

    /**
     * skills.uninstall - 卸载托管的技能。
     *
     * @param req    请求帧
     * @param params 参数（key: "github"）
     */
    private void handleSkillsUninstall(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) { sendError(req.id, "INVALID_PARAMS", "Missing key"); return; }

        boolean removed = sm.uninstall(key);
        if (removed) {
            sendResponse(req.id, Map.of("ok", true, "removed", key));
            sendEvent("skills.uninstalled", Map.of("key", key));
        } else {
            sendError(req.id, "NOT_FOUND", "Skill not found or not a managed skill: " + key);
        }
    }

    /**
     * skills.update - 更新托管技能到商店最新版本。
     *
     * @param req    请求帧
     * @param params 参数（key: "github"，key 必须与商店技能 ID 匹配）
     */
    private void handleSkillsUpdate(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) { sendError(req.id, "INVALID_PARAMS", "Missing key"); return; }

        sendResponse(req.id, Map.of("ok", true, "updating", true, "key", key));
        executor().submit(() -> {
            SkillInstaller.InstallResult result = sm.update(key);
            if (result.success) {
                sendEvent("skills.updated", Map.of(
                    "key", key,
                    "name", result.skillName,
                    "path", result.installDir.toString()
                ));
            } else {
                sendEvent("skills.update.error", Map.of(
                    "key", key,
                    "error", result.error != null ? result.error : "unknown"
                ));
            }
        });
    }

    /**
     * skills.reload - 强制从磁盘重新加载所有技能（无需重启服务）。
     *
     * @param req 请求帧
     */
    private void handleSkillsReload(GatewayProtocol.RequestFrame req) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }
        sm.reloadAll();
        sendResponse(req.id, Map.of(
            "ok", true,
            "total", sm.getAllSkills().size(),
            "countBySource", sm.getSkillCountBySource()
        ));
        sendEvent("skills.reloaded", Map.of("total", sm.getAllSkills().size()));
    }

    /**
     * skills.enable - 启用指定技能（运行时生效，不持久化到配置文件）。
     *
     * @param req    请求帧
     * @param params 参数（key: "my-skill"）
     *
     * 响应：{ ok: true, key: "my-skill" }
     * 事件："skills.enabled" { key: "my-skill" }
     */
    private void handleSkillsEnable(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }

        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) {
            sendError(req.id, "INVALID_PARAMS", "Missing required param: key"); return;
        }

        boolean changed = sm.enableSkill(key);
        if (changed) {
            sendResponse(req.id, Map.of("ok", true, "key", key, "enabled", true));
            sendEvent("skills.enabled", Map.of("key", key));
        } else {
            sendError(req.id, "NOT_FOUND", "Skill not found: " + key);
        }
    }

    /**
     * skills.disable - 禁用指定技能（运行时生效，不持久化到配置文件）。
     *
     * @param req    请求帧
     * @param params 参数（key: "my-skill"）
     *
     * 响应：{ ok: true, key: "my-skill" }
     * 事件："skills.disabled" { key: "my-skill" }
     */
    private void handleSkillsDisable(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }

        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) {
            sendError(req.id, "INVALID_PARAMS", "Missing required param: key"); return;
        }

        boolean changed = sm.disableSkill(key);
        if (changed) {
            sendResponse(req.id, Map.of("ok", true, "key", key, "enabled", false));
            sendEvent("skills.disabled", Map.of("key", key));
        } else {
            sendError(req.id, "NOT_FOUND", "Skill not found: " + key);
        }
    }

    /**
     * skills.scan - 对托管技能目录运行安全扫描。
     *
     * @param req    请求帧
     * @param params 参数（key: "my-skill"）
     *
     * 响应：{ ok: true, key, scannedFiles, critical, warn, info, findings: [...] }
     */
    private void handleSkillsScan(GatewayProtocol.RequestFrame req, JsonNode params) {
        SkillManager sm = agentManager.getSkillManager();
        if (sm == null) { sendError(req.id, "SKILL_MANAGER_DISABLED", "Skills not configured"); return; }

        String key = params != null && params.has("key") ? params.get("key").asText() : null;
        if (key == null || key.isBlank()) {
            sendError(req.id, "INVALID_PARAMS", "Missing required param: key"); return;
        }

        ai.openclaw.skills.SkillScanner.ScanSummary scan = sm.scanSkill(key);
        if (scan == null) {
            sendError(req.id, "NOT_FOUND", "Skill not found or not a managed skill: " + key); return;
        }

        List<Map<String, Object>> findingsOut = scan.findings.stream().map(f -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ruleId", f.ruleId);
            m.put("severity", f.severity.name().toLowerCase());
            m.put("file", f.file);
            m.put("line", f.line);
            m.put("message", f.message);
            m.put("evidence", f.evidence);
            return m;
        }).collect(java.util.stream.Collectors.toList());

        sendResponse(req.id, Map.of(
            "ok", true,
            "key", key,
            "scannedFiles", scan.scannedFiles,
            "critical", scan.critical,
            "warn", scan.warn,
            "info", scan.info,
            "safe", !scan.hasBlockingFindings(),
            "findings", findingsOut
        ));
    }

    /** 异步安装/更新任务使用的共享线程池（延迟初始化）。 */
    private ExecutorService executor() { return skillsExecutor; }

    // ─── 渠道管理 ────────────────────────────────────────────────────────────────

    /**
     * 获取各渠道的状态信息。
     *
     * @param req 请求帧
     */
    private void handleChannelsStatus(GatewayProtocol.RequestFrame req) {
        List<Map<String, Object>> channels = new ArrayList<>();

        // WebChat
        Map<String, Object> webChat = new LinkedHashMap<>();
        webChat.put("id", "webchat");
        webChat.put("name", "Web Chat");
        webChat.put("enabled", configManager.getConfig().channels.webChat.enabled);
        webChat.put("status", configManager.getConfig().channels.webChat.enabled ? "online" : "disabled");
        channels.add(webChat);

        // Telegram
        if (configManager.getConfig().channels.telegram != null) {
            Map<String, Object> telegram = new LinkedHashMap<>();
            telegram.put("id", "telegram");
            telegram.put("name", "Telegram");
            telegram.put("enabled", configManager.getConfig().channels.telegram.enabled);
            telegram.put("status", configManager.getConfig().channels.telegram.enabled ? "online" : "disabled");
            channels.add(telegram);
        }

        // Feishu (飞书)
        if (configManager.getConfig().channels.feishu != null) {
            Map<String, Object> feishu = new LinkedHashMap<>();
            feishu.put("id", "feishu");
            feishu.put("name", "飞书");
            feishu.put("enabled", configManager.getConfig().channels.feishu.enabled);
            feishu.put("status", configManager.getConfig().channels.feishu.enabled ? "online" : "disabled");
            channels.add(feishu);
        }

        sendResponse(req.id, channels);
    }

    // ─── 定时任务管理 ────────────────────────────────────────────────────────────────

    /**
     * 列出所有定时任务。
     *
     * @param req 请求帧
     */
    private void handleCronList(GatewayProtocol.RequestFrame req) {
        sendResponse(req.id, configManager.getConfig().cron);
    }

    /**
     * 添加定时任务。
     *
     * @param req    请求帧
     * @param params 任务配置
     * @throws Exception 配置解析异常
     */
    private void handleCronAdd(GatewayProtocol.RequestFrame req, JsonNode params) throws Exception {
        if (params == null) { sendError(req.id, "INVALID_PARAMS", "Missing params"); return; }
        OpenClaw4jConfig.CronJobConfig job = mapper.treeToValue(params, OpenClaw4jConfig.CronJobConfig.class);
        if (job.id == null) job.id = UUID.randomUUID().toString();
        configManager.getConfig().cron.add(job);
        configManager.save();
        sendResponse(req.id, Map.of("ok", true, "id", job.id));
    }

    /**
     * 删除定时任务。
     *
     * @param req    请求帧
     * @param params 参数（id）
     * @throws IOException IO 异常
     */
    private void handleCronRemove(GatewayProtocol.RequestFrame req, JsonNode params) throws IOException {
        String id = params != null && params.has("id") ? params.get("id").asText() : null;
        if (id == null) { sendError(req.id, "INVALID_PARAMS", "Missing id"); return; }
        configManager.getConfig().cron.removeIf(j -> id.equals(j.id));
        configManager.save();
        sendResponse(req.id, Map.of("ok", true));
    }

    /**
     * 立即执行定时任务。
     *
     * @param req    请求帧
     * @param params 参数（id）
     */
    private void handleCronRun(GatewayProtocol.RequestFrame req, JsonNode params) {
        String id = params != null && params.has("id") ? params.get("id").asText() : null;
        if (id == null) { sendError(req.id, "INVALID_PARAMS", "Missing id"); return; }

        configManager.getConfig().cron.stream()
            .filter(j -> id.equals(j.id))
            .findFirst()
            .ifPresentOrElse(job -> {
                agentManager.runAsync(job.agentId, "cron:" + job.id, job.prompt, new AgentManager.AgentCallback() {
                    @Override public void onChunk(String chunk) {}
                    @Override public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                        sendEvent("cron", Map.of("jobId", id, "status", "completed", "result", fullResponse));
                    }
                    @Override public void onError(String error) {
                        sendEvent("cron", Map.of("jobId", id, "status", "failed", "error", error));
                    }
                });
                sendResponse(req.id, Map.of("ok", true, "running", true));
            }, () -> sendError(req.id, "NOT_FOUND", "Cron job not found: " + id));
    }

    // ─── 用量统计 ────────────────────────────────────────────────────────────────

    /**
     * 获取 Token 用量统计。
     *
     * @param req 请求帧
     */
    private void handleUsageStatus(GatewayProtocol.RequestFrame req) {
        sendResponse(req.id, agentManager.getUsageStats());
    }

    // ─── 日志查询 ────────────────────────────────────────────────────────────────

    /**
     * 获取最近的日志记录。
     *
     * @param req    请求帧
     * @param params 参数（lines）
     */
    private void handleLogsTail(GatewayProtocol.RequestFrame req, JsonNode params) {
        int lines = params != null && params.has("lines") ? params.get("lines").asInt(100) : 100;
        sendResponse(req.id, ai.openclaw.util.LogBuffer.getInstance().tail(lines));
    }

    // ─── 消息发送辅助方法 ────────────────────────────────────────────────────────────

    /**
     * 向客户端发送事件推送。
     *
     * @param event   事件名称
     * @param payload 事件数据
     */
    public void sendEvent(String event, Object payload) {
        GatewayProtocol.EventFrame frame = GatewayProtocol.EventFrame.of(event, payload);
        frame.seq = seqCounter.incrementAndGet();
        send(frame);
    }

    /**
     * 向客户端发送成功响应。
     *
     * @param id      请求 ID
     * @param payload 响应数据
     */
    public void sendResponse(String id, Object payload) {
        send(GatewayProtocol.ResponseFrame.ok(id, payload));
    }

    /**
     * 向客户端发送错误响应。
     *
     * @param id      请求 ID
     * @param code    错误代码
     * @param message 错误消息
     */
    public void sendError(String id, String code, String message) {
        send(GatewayProtocol.ResponseFrame.error(id, code, message));
    }

    /**
     * 底层发送方法，将帧序列化为 JSON 并通过 WebSocket 发送。
     *
     * @param frame 要发送的协议帧
     */
    private void send(Object frame) {
        if (wsSession == null || !wsSession.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(frame);
            wsSession.getRemote().sendString(json, WriteCallback.NOOP);
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }
}
