package ai.openclaw.gateway;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.channels.FeishuChannel;
import ai.openclaw.channels.TelegramChannel;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.cron.CronManager;
import ai.openclaw.skills.SkillManager;
import ai.openclaw.tools.builtin.CronTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * 网关服务器主类：整合 WebSocket 与 HTTP 端点。
 * 所有服务运行在单一端口上（默认端口 18789）。
 * 负责初始化并启动 Jetty 嵌入式服务器，注册所有 Servlet 与 WebSocket 路由，
 * 并协调 AgentManager 的生命周期管理。
 */
public class GatewayServer {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    // 服务器监听端口
    private final int port;
    // 配置管理器，负责读取和管理应用配置
    private final ConfigManager configManager;
    // 是否启用详细日志输出
    private final boolean verbose;
    // Jetty 嵌入式 HTTP/WebSocket 服务器实例
    private final Server server;
    // JSON 序列化/反序列化工具
    private final ObjectMapper mapper;
    // WebSocket 会话注册中心，管理所有活跃连接
    private final GatewaySessionRegistry sessionRegistry;
    // Agent 管理器，负责 AI Agent 的创建与调度
    private final AgentManager agentManager;
    // Servlet 上下文处理器
    private ServletContextHandler context;
    // Telegram 通道
    private TelegramChannel telegramChannel;
    // 飞书通道
    private FeishuChannel feishuChannel;
    // 定时任务管理器
    private CronManager cronManager;

    /**
     * 构造函数，初始化网关服务器的所有核心组件。
     *
     * @param port          服务器监听端口
     * @param configManager 配置管理器实例
     * @param verbose       是否开启详细日志模式
     */
    public GatewayServer(int port, ConfigManager configManager, boolean verbose) {
        this.port = port;
        this.configManager = configManager;
        this.verbose = verbose;

        // 创建 ObjectMapper 并注册 Java 8 时间模块，支持 LocalDateTime 等类型序列化
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        // 初始化 WebSocket 会话注册中心
        this.sessionRegistry = new GatewaySessionRegistry();
        // 初始化 Agent 管理器，依赖配置、会话注册中心和 JSON 工具
        this.agentManager = new AgentManager(configManager, sessionRegistry, mapper);
        // 创建 Jetty 服务器实例
        this.server = new Server();
    }

    /**
     * 启动网关服务器，完成以下初始化步骤：
     * 1. 配置网络连接器（绑定地址和端口）
     * 2. 创建 Servlet 上下文
     * 3. 注册 WebSocket 端点（/ws）
     * 4. 注册各 HTTP Servlet（健康检查、API、Webhook、UI、根路径重定向）
     * 5. 启动 Jetty 服务器
     * 6. 启动 AgentManager
     *
     * @throws Exception 服务器启动失败时抛出异常
     */
    public void start() throws Exception {
        // 配置网络连接器，设置监听端口
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);

        // 根据配置决定绑定地址：localhost 仅本机访问，lan 绑定所有网卡
        String bind = configManager.getConfig().gateway.bind;
        if ("localhost".equals(bind)) {
            connector.setHost("127.0.0.1"); // 仅允许本机访问
        } else if ("lan".equals(bind)) {
            connector.setHost("0.0.0.0");   // 允许局域网访问
        }
        server.addConnector(connector);

        // 创建主 Servlet 上下文，启用 HTTP Session 支持，根路径为 "/"
        context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // 在 /ws 路径上配置 WebSocket 支持
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setIdleTimeout(Duration.ofDays(1)); // 实际上禁用空闲超时（1天）
            wsContainer.setMaxTextMessageSize(10 * 1024 * 1024); // 最大文本消息大小 10MB
            // 为每个新连接创建 GatewayWebSocket 实例
            wsContainer.addMapping("/ws", (upgradeRequest, upgradeResponse) ->
                new GatewayWebSocket(mapper, configManager, agentManager, sessionRegistry)
            );
        });

        // 注册 REST API 端点：/healthz 健康检查，/api/* 通用 API
        context.addServlet(new ServletHolder(new HealthServlet()), "/healthz");
        context.addServlet(new ServletHolder(new ApiServlet(configManager, agentManager, sessionRegistry, mapper)), "/api/*");

        // 注册 Webhook 端点，处理外部触发的 Agent 任务
        context.addServlet(new ServletHolder(new WebhookServlet(configManager, agentManager, mapper)), "/webhook/*");

        // 注册静态 UI 资源服务，从 classpath 加载（兼容 fat JAR 打包方式）
        context.addServlet(new ServletHolder(new UiServlet()), "/ui/*");

        // 根路径重定向到 /ui/，方便用户直接访问
        context.addServlet(new ServletHolder(new RootRedirectServlet()), "/");

        server.setHandler(context);
        server.start(); // 启动 Jetty 服务器

        // 启动 Agent 管理器，开始监听和处理 Agent 任务
        agentManager.start();

        // 启动定时任务管理器
        startCronManager();

        // 启动消息通道
        startChannels();

        log.info("Gateway server started on port {}", port);
    }

    /**
     * 优雅地停止网关服务器。
     * 先停止消息通道，再停止 AgentManager（等待当前任务完成），最后停止 Jetty 服务器。
     */
    public void stop() {
        try {
            stopChannels();      // 停止消息通道
            stopCronManager();   // 停止定时任务管理器
            agentManager.stop(); // 停止 Agent 管理器
            server.stop();       // 停止 Jetty 服务器
        } catch (Exception e) {
            log.error("Error stopping server", e);
        }
    }

    /**
     * 阻塞当前线程，直到服务器停止运行。
     * 通常在主线程调用，防止 JVM 在服务器运行期间退出。
     *
     * @throws InterruptedException 线程被中断时抛出
     */
    public void join() throws InterruptedException {
        server.join();
    }

    // ─── 消息通道管理 ─────────────────────────────────────────────────────

    /**
     * 启动所有配置的消息通道。
     */
    private void startChannels() {
        // 启动 Telegram 通道
        try {
            telegramChannel = new TelegramChannel(configManager, agentManager);
            telegramChannel.start();
            log.info("Telegram channel started: {}", telegramChannel.getStatus());
        } catch (Exception e) {
            log.error("Failed to start Telegram channel", e);
        }

        // 启动飞书通道
        try {
            feishuChannel = new FeishuChannel(configManager, agentManager);
            feishuChannel.start();

            // 注册飞书事件 Webhook
            if (feishuChannel.isRunning() && context != null) {
                String webhookPath = feishuChannel.getConfig() != null && feishuChannel.getConfig().webhookPath != null
                    ? feishuChannel.getConfig().webhookPath
                    : "/feishu/events";
                context.addServlet(new ServletHolder(new FeishuWebhookServlet(feishuChannel, mapper)), webhookPath);
                log.info("Feishu webhook registered at {}", webhookPath);
            }

            log.info("Feishu channel started: {}", feishuChannel.getStatus());
        } catch (Exception e) {
            log.error("Failed to start Feishu channel", e);
        }
    }

    /**
     * 停止所有消息通道。
     */
    private void stopChannels() {
        if (telegramChannel != null) {
            telegramChannel.stop();
            log.info("Telegram channel stopped");
        }
        if (feishuChannel != null) {
            feishuChannel.stop();
            log.info("Feishu channel stopped");
        }
    }

    /**
     * 获取 Telegram 通道实例。
     *
     * @return Telegram 通道实例，如果未启动则返回 null
     */
    public TelegramChannel getTelegramChannel() {
        return telegramChannel;
    }

    /**
     * 获取飞书通道实例。
     *
     * @return 飞书通道实例，如果未启动则返回 null
     */
    public FeishuChannel getFeishuChannel() {
        return feishuChannel;
    }

    // ─── 定时任务管理 ─────────────────────────────────────────────────────

    /**
     * 启动定时任务管理器。
     */
    private void startCronManager() {
        try {
            cronManager = new CronManager(configManager, agentManager, sessionRegistry);
            cronManager.start();

            // 注入 CronTool 到 AgentManager
            CronTool cronTool = new CronTool(cronManager, configManager);
            agentManager.registerDynamicTool(cronTool);
            log.info("CronTool registered as dynamic tool");
        } catch (Exception e) {
            log.error("Failed to start CronManager", e);
        }
    }

    /**
     * 停止定时任务管理器。
     */
    private void stopCronManager() {
        if (cronManager != null) {
            cronManager.stop();
            log.info("CronManager stopped");
        }
    }

    /**
     * 获取定时任务管理器实例。
     *
     * @return 定时任务管理器实例，如果未启动则返回 null
     */
    public CronManager getCronManager() {
        return cronManager;
    }

}
