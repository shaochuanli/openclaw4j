package ai.openclaw.config;

import ai.openclaw.skills.SkillsConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/**
 * OpenClaw4j根配置类。
 * <p>
 * 对应openclaw的OpenClawConfig结构，包含网关、模型、智能体、渠道、会话等所有配置项。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenClaw4jConfig {

    /** 网关服务器配置 */
    public GatewayConfig gateway = new GatewayConfig();

    /** AI模型提供商和模型配置 */
    public ModelsConfig models = new ModelsConfig();

    /** 多个智能体配置 */
    public AgentsConfig agents = new AgentsConfig();

    /** 渠道配置（如WebChat、Telegram） */
    public ChannelsConfig channels = new ChannelsConfig();

    /** 会话管理配置 */
    public SessionConfig session = new SessionConfig();

    /** 定时任务配置列表 */
    public List<CronJobConfig> cron = new ArrayList<>();

    /** 钩子配置 */
    public HooksConfig hooks = new HooksConfig();

    /** UI界面定制配置 */
    public UiConfig ui = new UiConfig();

    /** 日志配置 */
    public LoggingConfig logging = new LoggingConfig();

    /**
     * Skills子系统配置。
     * <p>
     * 控制SkillHub商店URL、本地技能目录、热重载和每个技能的覆盖配置。
     * 默认商店地址: https://skillhub.tencent.com
     * </p>
     */
    public SkillsConfig skills = new SkillsConfig();

    // ─── 嵌套配置类型 ────────────────────────────────────────────────

    /**
     * 网关配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GatewayConfig {
        // 服务端口，默认18789
        public int port = 18789;
        // 绑定地址: "localhost" | "lan" | "all"
        public String bind = "localhost";
        // 认证配置
        public AuthConfig auth = new AuthConfig();
        // 控制UI配置
        public ControlUiConfig controlUi = new ControlUiConfig();
    }

    /**
     * 认证配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthConfig {
        // 认证模式: "none" | "token" | "password"
        public String mode = "token";
        // 共享令牌（token模式使用）
        public String token;
        // 密码（password模式使用）
        public String password;
    }

    /**
     * 控制UI配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ControlUiConfig {
        // 是否启用控制UI
        public boolean enabled = true;
        // UI访问路径
        public String path = "/ui";
    }

    /**
     * 模型配置。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelsConfig {
        /** 提供商ID到配置的映射 */
        public Map<String, ModelProviderConfig> providers = new LinkedHashMap<>();
    }

    /**
     * 模型提供商配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelProviderConfig {
        // API类型: "openai-completions" | "anthropic-messages" | "ollama"
        public String api;
        // API基础URL
        public String baseUrl;
        // API密钥
        public String apiKey;
        // 支持的模型列表
        public List<ModelDefinitionConfig> models = new ArrayList<>();
    }

    /**
     * 模型定义配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelDefinitionConfig {
        // 模型ID
        public String id;
        // 模型显示名称
        public String name;
        // 支持的输入类型
        public List<String> input = Arrays.asList("text");
        // 是否为推理模型
        public boolean reasoning = false;
        // 上下文窗口大小（token数）
        public int contextWindow = 128000;
        // 最大输出token数
        public int maxTokens = 8192;
        // 成本配置
        public CostConfig cost = new CostConfig();
    }

    /**
     * 成本配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CostConfig {
        // 输入token单价（每百万token）
        public double input = 0;
        // 输出token单价（每百万token）
        public double output = 0;
        // 缓存读取token单价
        public double cacheRead = 0;
        // 缓存写入token单价
        public double cacheWrite = 0;
    }

    /**
     * 智能体配置集合。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentsConfig {
        /** 智能体列表 */
        public List<AgentConfig> agents = new ArrayList<>();
        /** 默认智能体ID */
        public String defaultAgentId = "default";
    }

    /**
     * 单个智能体配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentConfig {
        // 智能体唯一标识
        public String id = "default";
        // 智能体显示名称
        public String name = "Assistant";
        // 使用的模型引用: "providerId/modelId" 或别名
        public String model = "gpt-4o";
        // 系统提示词
        public String systemPrompt;
        // 角色设定
        public String persona;
        // 温度参数（创造性/随机性）
        public double temperature = 0.7;
        // 最大生成token数
        public int maxTokens = 4096;
        // 启用的技能列表
        public List<String> skills = new ArrayList<>();
        // 身份配置（头像、描述等）
        public IdentityConfig identity = new IdentityConfig();
    }

    /**
     * 身份配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IdentityConfig {
        // 名称
        public String name = "Assistant";
        // 头像URL
        public String avatar;
        // 描述信息
        public String description;
    }

    /**
     * 渠道配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChannelsConfig {
        // WebChat配置
        public WebChatConfig webChat = new WebChatConfig();
        // Telegram配置
        public TelegramConfig telegram;
        // 飞书配置
        public FeishuConfig feishu;
    }

    /**
     * WebChat配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebChatConfig {
        // 是否启用
        public boolean enabled = true;
        // 聊天窗口标题
        public String title = "OpenClaw4j Chat";
    }

    /**
     * Telegram配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TelegramConfig {
        // 是否启用
        public boolean enabled = false;
        // Bot Token
        public String token;
        // 私信策略: "open" | "pairing" | "closed"
        public String dmPolicy = "pairing";
        // 允许的用户列表
        public List<String> allowFrom = new ArrayList<>();
    }

    /**
     * 飞书配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FeishuConfig {
        // 是否启用
        public boolean enabled = false;
        // 应用ID (App ID)
        public String appId;
        // 应用密钥 (App Secret)
        public String appSecret;
        // 加密密钥 (Encrypt Key)，可选
        public String encryptKey;
        // 验证令牌 (Verification Token)，可选
        public String verificationToken;
        // 域名: "feishu" (国内) | "lark" (国际) | 自定义URL
        public String domain = "feishu";
        // 连接模式: "websocket" | "webhook"
        public String connectionMode = "websocket";
        // Webhook路径（webhook模式使用）
        public String webhookPath = "/feishu/events";
        // 私信策略: "open" | "pairing" | "closed"
        public String dmPolicy = "pairing";
        // 群聊策略: "open" | "allowlist" | "closed"
        public String groupPolicy = "allowlist";
        // 允许的用户列表（用户open_id）
        public List<String> allowFrom = new ArrayList<>();
        // 允许的群聊列表（群聊ID）
        public List<String> groupAllowFrom = new ArrayList<>();
    }

    /**
     * 会话配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SessionConfig {
        // 会话范围: "per-sender" | "global"
        public String scope = "per-sender";
        // 最大历史消息数
        public int maxHistoryMessages = 100;
        // 重置会话命令
        public String resetCommand = "/new";
        // 是否持久化历史记录
        public boolean persistHistory = true;
    }

    /**
     * 定时任务配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CronJobConfig {
        // 任务ID
        public String id;
        // 任务名称
        public String name;
        // Cron表达式
        public String schedule;
        // 使用的智能体ID
        public String agentId = "default";
        // 提示词
        public String prompt;
        // 是否启用
        public boolean enabled = true;
    }

    /**
     * 钩子配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HooksConfig {
        // Webhook列表
        public List<WebhookConfig> webhooks = new ArrayList<>();
        // 是否启用会话记忆
        public boolean sessionMemory = true;
        // 是否启用命令日志
        public boolean commandLogger = true;
    }

    /**
     * Webhook配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebhookConfig {
        // Webhook ID
        public String id;
        // 访问路径
        public String path;
        // 使用的智能体ID
        public String agentId = "default";
        // 密钥（用于验证）
        public String secret;
    }

    /**
     * UI配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UiConfig {
        // 助手名称
        public String assistantName = "OpenClaw4j";
        // 助手头像
        public String assistantAvatar;
        // 主题: "dark" | "light"
        public String theme = "dark";
        // 强调色
        public String accentColor = "#FF5F36";
    }

    /**
     * 日志配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LoggingConfig {
        // 日志级别
        public String level = "INFO";
        // 是否写入文件
        public boolean file = true;
        // 日志文件路径
        public String filePath;
    }
}
