package ai.openclaw.gateway;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 为非 WebSocket 客户端提供 REST API 接口的 Servlet。
 * 提供以下 JSON API 端点：
 *
 * GET 端点（部分需要认证）：
 *   GET  /api/health   - 健康检查，返回服务状态和版本（无需认证）
 *   GET  /api/config   - 返回当前运行配置（需要认证）
 *   GET  /api/models   - 返回可用的 AI 模型列表（需要认证）
 *   GET  /api/sessions - 返回当前所有会话列表（需要认证）
 *
 * POST 端点（均需认证）：
 *   POST /api/chat     - 同步发送消息给 Agent，等待完整响应后返回
 *
 * 支持 Bearer Token 和 URL 查询参数两种认证方式。
 * 所有响应均为 JSON 格式，并设置 CORS 头以支持跨域访问。
 */
public class ApiServlet extends HttpServlet {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(ApiServlet.class);

    // 配置管理器，用于读取认证配置和应用配置
    private final ConfigManager configManager;
    // Agent 管理器，用于执行 AI 对话任务
    private final AgentManager agentManager;
    // WebSocket 会话注册中心，用于查询活跃连接数
    private final GatewaySessionRegistry sessionRegistry;
    // JSON 序列化/反序列化工具
    private final ObjectMapper mapper;

    /**
     * 构造 ApiServlet，注入所有依赖。
     *
     * @param configManager   配置管理器
     * @param agentManager    Agent 管理器
     * @param sessionRegistry WebSocket 会话注册中心
     * @param mapper          JSON 工具
     */
    public ApiServlet(ConfigManager configManager, AgentManager agentManager,
                      GatewaySessionRegistry sessionRegistry, ObjectMapper mapper) {
        this.configManager = configManager;
        this.agentManager = agentManager;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
    }

    /**
     * 处理 GET 请求，根据路径分发到对应的处理逻辑。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException IO 异常
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        setCors(res); // 设置 CORS 响应头，允许跨域访问
        String path = req.getPathInfo();
        if (path == null) path = "/";

        switch (path) {
            case "/health" -> json(res, Map.of(
                "status", "ok",
                "version", ai.openclaw.OpenClaw4j.VERSION,
                "connections", sessionRegistry.size() // 当前 WebSocket 活跃连接数
            ));
            case "/config" -> {
                if (!authenticate(req, res)) return; // 需要认证
                res.setContentType("application/json");
                res.getWriter().write(configManager.toJson()); // 返回完整配置 JSON
            }
            case "/models" -> {
                if (!authenticate(req, res)) return; // 需要认证
                json(res, agentManager.listModels()); // 返回可用模型列表
            }
            case "/sessions" -> {
                if (!authenticate(req, res)) return; // 需要认证
                json(res, agentManager.getSessionManager().listSessions()); // 返回所有会话
            }
            default -> {
                res.setStatus(404);
                json(res, Map.of("error", "Not found")); // 未知路径，返回 404
            }
        }
    }

    /**
     * 处理 POST 请求，目前支持 /api/chat 同步对话接口。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException      IO 异常
     * @throws jakarta.servlet.ServletException Servlet 异常
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException, jakarta.servlet.ServletException {
        setCors(res); // 设置 CORS 响应头
        String path = req.getPathInfo();
        if (path == null) path = "/";

        if (!authenticate(req, res)) return; // 所有 POST 请求均需认证

        switch (path) {
            case "/chat" -> {
                // 解析请求体 JSON
                var body = mapper.readTree(req.getInputStream());
                // 读取 agentId，默认为 "default"
                String agentId = body.has("agentId") ? body.get("agentId").asText() : "default";
                // 读取 sessionKey，默认为 "api:default"
                String sessionKey = body.has("sessionKey") ? body.get("sessionKey").asText() : "api:default";
                // 读取用户消息文本
                String text = body.has("text") ? body.get("text").asText() : "";

                // 校验消息非空
                if (text.isBlank()) {
                    res.setStatus(400);
                    json(res, Map.of("error", "Empty message"));
                    return;
                }

                // 同步调用 Agent，等待完整响应（适用于 HTTP REST 客户端）
                AgentManager.AgentRunResult result;
                try {
                    result = agentManager.runSync(agentId, sessionKey, text);
                } catch (Exception e) {
                    res.setStatus(500);
                    json(res, Map.of("error", e.getMessage() != null ? e.getMessage() : "Agent error"));
                    return;
                }
                // 返回响应文本和 Token 用量统计
                json(res, Map.of(
                    "ok", true,
                    "response", result.response,
                    "usage", Map.of(
                        "inputTokens", result.usage.inputTokens,   // 输入 Token 数
                        "outputTokens", result.usage.outputTokens  // 输出 Token 数
                    )
                ));
            }
            default -> {
                res.setStatus(404);
                json(res, Map.of("error", "Not found")); // 未知路径，返回 404
            }
        }
    }

    /**
     * 处理 OPTIONS 预检请求，支持浏览器 CORS 跨域。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     */
    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) {
        setCors(res);         // 设置 CORS 响应头
        res.setStatus(200);   // 返回 200 表示允许跨域
    }

    /**
     * 验证 HTTP 请求的身份认证。
     *
     * 支持两种认证方式：
     * 1. HTTP Header：Authorization: Bearer &lt;token&gt;
     * 2. URL 查询参数：?token=&lt;token&gt;
     *
     * 若配置认证模式为 "none"，则跳过认证直接放行。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象（认证失败时写入 401 响应）
     * @return true 表示认证通过，false 表示认证失败（已写入 401 响应）
     * @throws IOException IO 异常
     */
    private boolean authenticate(HttpServletRequest req, HttpServletResponse res) throws IOException {
        var auth = configManager.getConfig().gateway.auth;
        if ("none".equals(auth.mode)) return true; // 无需认证模式，直接放行

        // 优先检查 Authorization Header（Bearer Token 方式）
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7); // 去除 "Bearer " 前缀，提取 Token
            String expected = configManager.resolveSecret(auth.token); // 解析配置中的期望 Token（支持环境变量引用）
            if (token.equals(expected)) return true;
        }

        // 其次检查 URL 查询参数中的 token（适用于简单场景）
        String tokenParam = req.getParameter("token");
        if (tokenParam != null) {
            String expected = configManager.resolveSecret(auth.token);
            if (tokenParam.equals(expected)) return true;
        }

        // 认证失败，返回 401 Unauthorized
        res.setStatus(401);
        json(res, Map.of("error", "Unauthorized"));
        return false;
    }

    /**
     * 将对象序列化为 JSON 并写入 HTTP 响应。
     *
     * @param res HTTP 响应对象
     * @param obj 要序列化的对象
     * @throws IOException IO 异常
     */
    private void json(HttpServletResponse res, Object obj) throws IOException {
        res.setContentType("application/json;charset=utf-8"); // 设置 JSON 内容类型
        mapper.writeValue(res.getWriter(), obj);
    }

    /**
     * 设置 CORS（跨域资源共享）响应头。
     *
     * 允许所有来源、GET/POST/OPTIONS 方法及 Content-Type/Authorization 头，
     * 以支持浏览器前端的跨域 API 调用。
     *
     * @param res HTTP 响应对象
     */
    private void setCors(HttpServletResponse res) {
        res.setHeader("Access-Control-Allow-Origin", "*");                              // 允许所有来源
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");            // 允许的 HTTP 方法
        res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");   // 允许的请求头
    }
}
