package ai.openclaw;

import ai.openclaw.config.ConfigManager;
import ai.openclaw.gateway.GatewayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OpenClaw4j - Java Edition Personal AI Assistant Gateway
 * Self-hosted AI assistant gateway supporting multiple channels and LLM providers.
 * 
 * OpenClaw4j - Java版个人AI助手网关
 * 自托管的AI助手网关，支持多种渠道和大型语言模型提供商
 */
public class OpenClaw4j {

    private static final Logger log = LoggerFactory.getLogger(OpenClaw4j.class);
    // 版本号
    public static final String VERSION = "1.0.0";
    // 默认配置目录路径（用户主目录下的 .openclaw4j 文件夹）
    public static final String DEFAULT_CONFIG_DIR = System.getProperty("user.home") + "/.openclaw4j";
    // 默认服务端口
    public static final int DEFAULT_PORT = 18789;

    /**
     * 程序入口点
     * @param args 命令行参数
     * @throws Exception 启动异常
     */
    public static void main(String[] args) throws Exception {
        printBanner();

        // 解析命令行参数
        int port = DEFAULT_PORT;
        boolean verbose = false;
        String configPath = DEFAULT_CONFIG_DIR + "/openclaw4j.json";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "--config", "-c" -> {
                    if (i + 1 < args.length) configPath = args[++i];
                }
                case "--verbose", "-v" -> verbose = true;
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                case "--version" -> {
                    System.out.println("openclaw4j v" + VERSION);
                    return;
                }
            }
        }

        // 确保配置目录存在
        Path configDir = Paths.get(DEFAULT_CONFIG_DIR);
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            log.info("Created config directory: {}", configDir);
        }

        // 初始化配置管理器
        ConfigManager configManager = new ConfigManager(configPath);
        try {
            configManager.load();
        } catch (IOException e) {
            log.warn("No config found, creating default config at: {}", configPath);
            configManager.createDefault(configPath);
        }

        // 启动网关服务
        GatewayServer gateway = new GatewayServer(port, configManager, verbose);
        gateway.start();

        log.info("🦞 OpenClaw4j v{} running on http://localhost:{}", VERSION, port);
        log.info("   Control Panel: http://localhost:{}/ui", port);
        log.info("   WebSocket:      ws://localhost:{}/ws", port);
        log.info("   Press Ctrl+C to stop.");

        // 注册关闭钩子，确保程序退出时优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down OpenClaw4j...");
            gateway.stop();
        }));

        gateway.join();
    }

    /**
     * 打印启动横幅
     */
    private static void printBanner() {
        System.out.println("""

                ██████╗ ██████╗ ███████╗███╗   ██╗ ██████╗██╗      █████╗ ██╗    ██╗██╗  ██╗     ██╗
                ██╔═══██╗██╔══██╗██╔════╝████╗  ██║██╔════╝██║     ██╔══██╗██║    ██║██║  ██║     ██║
                ██║   ██║██████╔╝█████╗  ██╔██╗ ██║██║     ██║     ███████║██║ █╗ ██║███████║     ██║
                ██║   ██║██╔═══╝ ██╔══╝  ██║╚██╗██║██║     ██║     ██╔══██║██║███╗██║╚════██║██   ██║
                ╚██████╔╝██║     ███████╗██║ ╚████║╚██████╗███████╗██║  ██║╚███╔███╔╝     ██║╚█████╔╝
                ╚═════╝ ╚═╝     ╚══════╝╚═╝  ╚═══╝ ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝      ╚═╝ ╚════╝ 

                  Java Edition - Personal AI Assistant Gateway  \uD83E\uDD9E
                """);
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("""
                OpenClaw4j - Personal AI Assistant Gateway (Java Edition)
                OpenClaw4j - 个人AI助手网关（Java版）
                
                Usage: java -jar openclaw4j.jar [options]
                用法: java -jar openclaw4j.jar [选项]
                
                Options:
                选项:
                  --port, -p <port>     Gateway port (default: 18789)
                                        网关端口（默认: 18789）
                  --config, -c <path>   Config file path
                                        配置文件路径
                  --verbose, -v         Verbose logging
                                        详细日志输出
                  --version             Show version
                                        显示版本号
                  --help, -h            Show this help
                                        显示帮助信息
                
                Config file: ~/.openclaw4j/openclaw4j.json
                配置文件: ~/.openclaw4j/openclaw4j.json
                Control Panel: http://localhost:18789/ui
                控制面板: http://localhost:18789/ui
                """);
    }
}
