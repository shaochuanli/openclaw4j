package ai.openclaw.gateway;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 根路径重定向 Servlet，映射到 "/" 路径。
 * 当用户直接访问服务器根路径（如 http://localhost:18789/）时，
 * 自动重定向到前端 UI 页面（/ui/），提升用户体验。
 */
public class RootRedirectServlet extends HttpServlet {

    /**
     * 处理 GET 请求，将根路径请求重定向到 /ui/。
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException IO 异常
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.sendRedirect("/ui/"); // HTTP 302 重定向到前端 UI
    }
}
