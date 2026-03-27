package ai.openclaw.channels;

import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;

/**
 * Telegram通道实现类。
 * <p>
 * 通过Telegram Bot API监听消息并响应，实现与用户的交互。
 * </p>
 */
public class TelegramChannel extends Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private final ConfigManager configManager;
    private final AgentManager agentManager;
    private TelegramLongPollingBot bot;
    private TelegramBotsApi botsApi;
    private String statusMessage = "offline";

    /**
     * 构造Telegram通道实例。
     *
     * @param configManager 配置管理器
     * @param agentManager  智能体管理器
     */
    public TelegramChannel(ConfigManager configManager, AgentManager agentManager) {
        super("telegram", "Telegram");
        this.configManager = configManager;
        this.agentManager = agentManager;
    }

    /**
     * 启动Telegram Bot，注册到Telegram服务器。
     *
     * @throws Exception 启动过程中发生异常
     */
    @Override
    public void start() throws Exception {
        OpenClaw4jConfig.TelegramConfig cfg = configManager.getConfig().channels.telegram;
        if (cfg == null || !cfg.enabled) {
            statusMessage = "disabled";
            return;
        }

        String token = configManager.resolveSecret(cfg.token);
        if (token == null || token.startsWith("${")) {
            statusMessage = "error: no token configured";
            log.warn("Telegram bot token not configured");
            return;
        }

        try {
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            bot = new OpenClaw4jBot(token, cfg, configManager, agentManager);
            botsApi.registerBot(bot);
            running = true;
            statusMessage = "online";
            log.info("Telegram channel started");
        } catch (Exception e) {
            statusMessage = "error: " + e.getMessage();
            log.error("Failed to start Telegram channel", e);
            throw e;
        }
    }

    /**
     * 停止Telegram Bot。
     */
    @Override
    public void stop() {
        running = false;
        statusMessage = "offline";
    }

    /**
     * 获取通道当前状态。
     *
     * @return 状态字符串
     */
    @Override
    public String getStatus() { return statusMessage; }

    // ─── Bot实现 ────────────────────────────────────────────────────

    /**
     * OpenClaw4j Telegram Bot内部实现类。
     * <p>
     * 继承TelegramLongPollingBot，处理接收到的消息并转发给智能体处理。
     * </p>
     */
    static class OpenClaw4jBot extends TelegramLongPollingBot {

        private final String token;
        private final OpenClaw4jConfig.TelegramConfig cfg;
        private final ConfigManager configManager;
        private final AgentManager agentManager;
        private final Set<String> pendingPairings = new HashSet<>();

        /**
         * 构造Bot实例。
         *
         * @param token         Bot Token
         * @param cfg           Telegram配置
         * @param configManager 配置管理器
         * @param agentManager  智能体管理器
         */
        OpenClaw4jBot(String token, OpenClaw4jConfig.TelegramConfig cfg,
                      ConfigManager configManager, AgentManager agentManager) {
            super(token);
            this.token = token;
            this.cfg = cfg;
            this.configManager = configManager;
            this.agentManager = agentManager;
        }

        /**
         * 获取Bot用户名。
         *
         * @return Bot用户名
         */
        @Override
        public String getBotUsername() { return "openclaw4j_bot"; }

        /**
         * 处理接收到的消息更新。
         * <p>
         * 解析消息内容，验证用户权限，并转发给智能体处理。
         * </p>
         *
         * @param update Telegram更新对象
         */
        @Override
        public void onUpdateReceived(Update update) {
            if (!update.hasMessage() || !update.getMessage().hasText()) return;

            String text = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userId = String.valueOf(update.getMessage().getFrom().getId());
            String username = update.getMessage().getFrom().getUserName();

            // 检查允许发消息的用户列表
            if (!cfg.allowFrom.isEmpty()) {
                boolean allowed = cfg.allowFrom.contains(userId) ||
                                  cfg.allowFrom.contains("@" + username);
                if (!allowed) {
                    sendReply(chatId, "⛔ You are not authorized to use this bot.");
                    return;
                }
            }

            // 处理命令
            if ("/start".equals(text) || "/help".equals(text)) {
                sendReply(chatId, """
                    🦞 *OpenClaw4j* — Your AI Assistant

                    Available commands:
                    • /new or /reset — Start fresh conversation
                    • /help — Show this help

                    Just send me a message to chat!
                    """);
                return;
            }

            String sessionKey = "telegram:" + chatId;

            agentManager.runAsync("default", sessionKey, text, new AgentManager.AgentCallback() {
                StringBuilder buffer = new StringBuilder();

                @Override public void onChunk(String chunk) {
                    buffer.append(chunk);
                }

                @Override
                public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                    sendReply(chatId, fullResponse);
                }

                @Override
                public void onError(String error) {
                    sendReply(chatId, "❌ Error: " + error);
                }
            });
        }

        /**
         * 发送回复消息到指定聊天。
         *
         * @param chatId 聊天ID
         * @param text   消息文本
         */
        private void sendReply(long chatId, String text) {
            try {
                SendMessage msg = new SendMessage();
                msg.setChatId(chatId);
                msg.setText(text);
                msg.setParseMode("Markdown");
                execute(msg);
            } catch (TelegramApiException e) {
                // 尝试不使用Markdown格式发送
                try {
                    SendMessage msg = new SendMessage();
                    msg.setChatId(chatId);
                    msg.setText(text);
                    execute(msg);
                } catch (TelegramApiException ex) {
                    log.error("Failed to send Telegram message", ex);
                }
            }
        }
    }
}
