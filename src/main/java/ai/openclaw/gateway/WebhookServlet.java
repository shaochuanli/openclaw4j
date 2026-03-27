package ai.openclaw.gateway;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * 处理外部系统通过 Webhook 触发 Agent 任务的 Servlet。
 * 映射路径：/webhook/{webhookId}
 *
 * 处理流程：
 * 1. 从 URL 路径中提取 webhookId
 * 2. 在配置中查找对应的 Webhook 配置（匹配 id 或 path 字段）
 * 3. 若配置了 secret，校验请求头 X-Webhook-Secret
 * 4. 从请求体中提取 prompt/text/message 作为 Agent 输入
 * 5. 异步执行 Agent 任务，立即返回 {ok: true, queued: true}
 *
 * 典型使用场景：GitHub Actions、CI/CD 系统、定时任务等触发 AI 自动化流程。
 */
public class WebhookServlet extends HttpServlet {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(WebhookServlet.class);

    // 配置管理器，用于查找 Webhook 配置
    private final ConfigManager configManager;
    // Agent 管理器，用于异步执行 Agent 任务
    private final AgentManager agentManager;
    // JSON 序列化/反序列化工具
    private final ObjectMapper mapper;

    /**
     * 构造 WebhookServlet，注入所有依赖。
     *
     * @param configManager 配置管理器
     * @param agentManager  Agent 管理器
     * @param mapper        JSON 工具
     */
    public WebhookServlet(ConfigManager configManager, AgentManager agentManager, ObjectMapper mapper) {
        this.configManager = configManager;
        this.agentManager = agentManager;
        this.mapper = mapper;
    }

    /**
     * 处理 POST 请求，验证 Webhook 配置并异步触发 Agent 任务。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException IO 异常
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        String path = req.getPathInfo();
        // 路径为空或仅为 "/" 时，缺少 webhookId，返回 400
        if (path == null || path.equals("/")) {
            res.setStatus(400);
            mapper.writeValue(res.getWriter(), Map.of("error", "Missing webhook ID"));
            return;
        }

        // 从路径中提取 webhookId（去除开头的 "/"）
        String webhookId = path.startsWith("/") ? path.substring(1) : path;

        // 在配置中查找匹配的 Webhook 定义（按 id 或 path 字段匹配）
        OpenClaw4jConfig.WebhookConfig webhookConfig = configManager.getConfig().hooks.webhooks.stream()
            .filter(w -> webhookId.equals(w.id) || webhookId.equals(w.path))
            .findFirst()
            .orElse(null);

        // Webhook 未在配置中注册，返回 404
        if (webhookConfig == null) {
            res.setStatus(404);
            mapper.writeValue(res.getWriter(), Map.of("error", "Webhook not found: " + webhookId));
            return;
        }

        // 若 Webhook 配置了 secret，则校验请求头 X-Webhook-Secret
        if (webhookConfig.secret != null && !webhookConfig.secret.isBlank()) {
            String sigHeader = req.getHeader("X-Webhook-Secret");
            if (!webhookConfig.secret.equals(sigHeader)) {
                res.setStatus(401);
                mapper.writeValue(res.getWriter(), Map.of("error", "Invalid webhook secret"));
                return;
            }
        }

        // 从请求体中提取 Agent 输入 prompt，按优先级依次尝试 "prompt" → "text" → "message"
        String prompt = "Webhook triggered: " + webhookId; // 默认 prompt，无请求体时使用
        try {
            JsonNode body = mapper.readTree(req.getInputStream());
            if (body.has("prompt")) {
                prompt = body.get("prompt").asText();       // 优先使用 "prompt" 字段
            } else if (body.has("text")) {
                prompt = body.get("text").asText();         // 其次使用 "text" 字段
            } else if (body.has("message")) {
                prompt = body.get("message").asText();      // 最后使用 "message" 字段
            }
        } catch (Exception ignored) {} // 请求体解析失败时使用默认 prompt

        final String finalPrompt = prompt;
        // 确定目标 Agent，默认使用 "default" Agent
        final String agentId = webhookConfig.agentId != null ? webhookConfig.agentId : "default";

        // 异步执行 Agent 任务，立即返回响应（避免 HTTP 超时）
        agentManager.runAsync(agentId, "webhook:" + webhookId, finalPrompt, new AgentManager.AgentCallback() {
            @Override public void onChunk(String chunk) {}  // 流式输出片段（此处忽略）
            @Override public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                // Agent 执行成功，记录响应长度
                log.info("Webhook {} completed, response length: {}", webhookId, fullResponse.length());
            }
            @Override public void onError(String error) {
                // Agent 执行失败，记录错误
                log.error("Webhook {} failed: {}", webhookId, error);
            }
        });

        // 立即返回已入队的响应，Agent 在后台异步执行
        mapper.writeValue(res.getWriter(), Map.of("ok", true, "queued", true));
    }
}
