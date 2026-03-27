package ai.openclaw.gateway;

import ai.openclaw.channels.FeishuChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/**
 * 飞书事件 Webhook Servlet。
 * <p>
 * 处理飞书开放平台推送的事件消息，包括：
 * - URL验证请求（challenge）
 * - 消息接收事件
 * </p>
 */
public class FeishuWebhookServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookServlet.class);

    private final FeishuChannel feishuChannel;
    private final ObjectMapper mapper;

    /**
     * 构造飞书 Webhook Servlet。
     *
     * @param feishuChannel 飞书通道实例
     * @param mapper        JSON 工具
     */
    public FeishuWebhookServlet(FeishuChannel feishuChannel, ObjectMapper mapper) {
        this.feishuChannel = feishuChannel;
        this.mapper = mapper;
    }

    /**
     * 处理 POST 请求，接收飞书事件。
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        // 检查飞书通道是否可用
        if (feishuChannel == null || !feishuChannel.isRunning()) {
            res.setStatus(503);
            mapper.writeValue(res.getWriter(), Map.of("error", "Feishu channel not available"));
            return;
        }

        // 读取请求体
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }

        String eventJson = body.toString();
        log.debug("Received Feishu webhook event: {}", eventJson.length() > 200 ? eventJson.substring(0, 200) + "..." : eventJson);

        // 委托给 FeishuChannel 处理
        String response = feishuChannel.handleWebhookEvent(eventJson);

        res.getWriter().write(response);
    }

    /**
     * 处理 GET 请求，用于健康检查。
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), Map.of(
            "status", "ok",
            "channel", "feishu",
            "running", feishuChannel != null && feishuChannel.isRunning()
        ));
    }
}