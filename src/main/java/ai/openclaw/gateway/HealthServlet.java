package ai.openclaw.gateway;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 简单的健康检查端点，映射到 /healthz 路径。
 * 用于 Kubernetes 存活探针（liveness probe）、负载均衡器健康检查
 * 以及监控系统的服务可用性探测。
 *
 * 响应示例：
 * &lt;pre&gt;
 *   HTTP 200 OK
 *   Content-Type: application/json
 *   {"status":"ok","version":"1.0.0"}
 * &lt;/pre&gt;
 */
public class HealthServlet extends HttpServlet {

    /**
     * 处理 GET 请求，返回服务健康状态和版本信息。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException IO 异常
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json"); // 设置响应内容类型为 JSON
        // 返回包含服务状态和版本号的 JSON 响应
        res.getWriter().write("{\"status\":\"ok\",\"version\":\"" + ai.openclaw.OpenClaw4j.VERSION + "\"}");
    }
}
