package ai.openclaw.gateway;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 直接从 classpath 提供静态 UI 文件服务（兼容 fat JAR 打包方式）。
 * 映射路径为 /ui/*，处理所有前端静态资源请求。
 *
 * 特性说明：
 * - 支持从 fat JAR 内嵌的 classpath 资源中读取文件，无需解压
 * - 实现 SPA（单页应用）回退策略：当请求的资源不存在时，回退到 /ui/index.html
 * - 根据文件扩展名自动设置正确的 Content-Type
 * - 禁用浏览器缓存，确保每次都加载最新版本的 JS/CSS 资源
 */
public class UiServlet extends HttpServlet {

    // 文件扩展名 → MIME 类型映射表
    private static final Map<String, String> MIME = Map.of(
        ".html", "text/html; charset=UTF-8",
        ".js",   "application/javascript; charset=UTF-8",
        ".css",  "text/css; charset=UTF-8",
        ".json", "application/json; charset=UTF-8",
        ".png",  "image/png",
        ".svg",  "image/svg+xml",
        ".ico",  "image/x-icon"
    );

    /**
     * 处理静态文件的 GET 请求，支持 SPA 路由回退。
     *
     * 处理流程：
     * 1. 解析请求路径，空路径默认映射到 /index.html
     * 2. 尝试从 classpath 的 /ui/ 目录下加载对应文件
     * 3. 若文件不存在，执行 SPA 回退，返回 /ui/index.html
     * 4. 设置正确的 Content-Type 和禁缓存响应头
     * 5. 将文件内容流式传输到响应输出流
     *
     * @param req HTTP 请求对象
     * @param res HTTP 响应对象
     * @throws IOException IO 异常
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = req.getPathInfo();   // 如 "/index.html" 或 "/"
        // 空路径或根路径默认映射到 index.html
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }

        // 构建 classpath 资源路径，从 /ui/ 目录下查找
        String resource = "/ui" + path;
        InputStream raw = getClass().getResourceAsStream(resource);

        final InputStream in;
        if (raw == null) {
            // 请求的资源不存在，执行 SPA 回退：返回 index.html，由前端路由处理
            InputStream fallback = getClass().getResourceAsStream("/ui/index.html");
            if (fallback == null) {
                res.sendError(404, "UI not found"); // UI 资源未找到（未打包 UI）
                return;
            }
            in = fallback;
            res.setContentType("text/html; charset=UTF-8"); // SPA 回退时统一返回 HTML 类型
        } else {
            in = raw;
            res.setContentType(mimeFor(path)); // 根据文件扩展名设置正确的 MIME 类型
        }

        res.setStatus(200);
        // 禁用浏览器缓存，确保每次请求都获取最新的前端资源（开发和热更新场景下尤为重要）
        res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        res.setHeader("Expires", "0");
        // 使用 try-with-resources 确保流在传输完成后被正确关闭
        try (InputStream toClose = in) {
            toClose.transferTo(res.getOutputStream()); // 流式传输文件内容到响应
        }
    }

    /**
     * 根据文件路径的扩展名查找对应的 MIME 类型。
     * 若扩展名不在预定义映射表中，默认返回二进制流类型。
     *
     * @param path 文件路径（含扩展名）
     * @return 对应的 MIME 类型字符串，未匹配时返回 "application/octet-stream"
     */
    private String mimeFor(String path) {
        for (var entry : MIME.entrySet()) {
            if (path.endsWith(entry.getKey())) return entry.getValue();
        }
        return "application/octet-stream"; // 未知类型，默认返回二进制流
    }
}
