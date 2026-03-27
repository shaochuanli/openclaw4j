package ai.openclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages loading, saving, and hot-reloading of OpenClaw4j configuration.
 * 
 * OpenClaw4j 配置管理器
 * 负责配置的加载、保存和热重载
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    // 配置文件路径
    private final String configPath;
    // JSON对象映射器
    private final ObjectMapper mapper;
    // 当前配置对象
    private OpenClaw4jConfig config;
    // 配置变更监听器列表
    private final List<Runnable> changeListeners = new ArrayList<>();

    /**
     * 构造配置管理器
     * @param configPath 配置文件路径
     */
    public ConfigManager(String configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper();
        // 启用格式化输出（带缩进）
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.config = new OpenClaw4jConfig();
    }

    /**
     * 从文件加载配置
     * @throws IOException 文件读取失败时抛出
     */
    public void load() throws IOException {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Config file not found: " + configPath);
        }
        this.config = mapper.readValue(path.toFile(), OpenClaw4jConfig.class);
        log.info("Configuration loaded from: {}", configPath);
    }

    /**
     * 创建默认配置文件
     * @param configPath 配置文件路径
     * @throws IOException 文件写入失败时抛出
     */
    public void createDefault(String configPath) throws IOException {
        OpenClaw4jConfig defaults = buildDefaultConfig();
        Path path = Paths.get(configPath);
        Files.createDirectories(path.getParent());
        mapper.writeValue(path.toFile(), defaults);
        this.config = defaults;
        log.info("Default configuration created at: {}", configPath);
    }

    /**
     * 构建默认配置
     * @return 默认配置对象
     */
    private OpenClaw4jConfig buildDefaultConfig() {
        OpenClaw4jConfig cfg = new OpenClaw4jConfig();

        // 默认网关配置
        cfg.gateway.port = 18789;
        cfg.gateway.bind = "localhost";
        cfg.gateway.auth.mode = "token";
        // 生成随机token
        cfg.gateway.auth.token = UUID.randomUUID().toString().replace("-", "");

        // 默认智能体配置
        OpenClaw4jConfig.AgentConfig agent = new OpenClaw4jConfig.AgentConfig();
        agent.id = "default";
        agent.name = "Assistant";
        agent.model = "openai/gpt-4o";
        agent.systemPrompt = "You are a helpful, intelligent assistant.";
        agent.identity.name = "Assistant";
        cfg.agents.agents.add(agent);

        // 默认OpenAI提供商配置（示例）
        OpenClaw4jConfig.ModelProviderConfig openaiProvider = new OpenClaw4jConfig.ModelProviderConfig();
        openaiProvider.api = "openai-completions";
        openaiProvider.baseUrl = "https://api.openai.com/v1";
        openaiProvider.apiKey = "${OPENAI_API_KEY}";

        // GPT-4o 模型配置
        OpenClaw4jConfig.ModelDefinitionConfig gpt4o = new OpenClaw4jConfig.ModelDefinitionConfig();
        gpt4o.id = "gpt-4o";
        gpt4o.name = "GPT-4o";
        gpt4o.input = Arrays.asList("text", "image");
        gpt4o.contextWindow = 128000;
        gpt4o.maxTokens = 4096;
        gpt4o.cost.input = 2.5;
        gpt4o.cost.output = 10.0;
        openaiProvider.models.add(gpt4o);

        // GPT-4o Mini 模型配置
        OpenClaw4jConfig.ModelDefinitionConfig gpt4oMini = new OpenClaw4jConfig.ModelDefinitionConfig();
        gpt4oMini.id = "gpt-4o-mini";
        gpt4oMini.name = "GPT-4o Mini";
        gpt4oMini.input = Arrays.asList("text", "image");
        gpt4oMini.contextWindow = 128000;
        gpt4oMini.maxTokens = 16384;
        gpt4oMini.cost.input = 0.15;
        gpt4oMini.cost.output = 0.6;
        openaiProvider.models.add(gpt4oMini);

        cfg.models.providers.put("openai", openaiProvider);

        // 默认Ollama提供商配置（本地模型）
        OpenClaw4jConfig.ModelProviderConfig ollamaProvider = new OpenClaw4jConfig.ModelProviderConfig();
        ollamaProvider.api = "ollama";
        ollamaProvider.baseUrl = "http://localhost:11434";

        // Llama 3.2 模型配置
        OpenClaw4jConfig.ModelDefinitionConfig llama3 = new OpenClaw4jConfig.ModelDefinitionConfig();
        llama3.id = "llama3.2";
        llama3.name = "Llama 3.2";
        llama3.input = Arrays.asList("text");
        llama3.contextWindow = 128000;
        llama3.maxTokens = 8192;
        ollamaProvider.models.add(llama3);

        cfg.models.providers.put("ollama", ollamaProvider);

        return cfg;
    }

    /**
     * 保存配置到文件
     * @throws IOException 文件写入失败时抛出
     */
    public void save() throws IOException {
        mapper.writeValue(Paths.get(configPath).toFile(), config);
        log.info("Configuration saved to: {}", configPath);
    }

    /**
     * 获取当前配置
     * @return 配置对象
     */
    public OpenClaw4jConfig getConfig() {
        return config;
    }

    /**
     * 重新加载配置并通知监听器
     */
    public void reload() {
        try {
            load();
            changeListeners.forEach(Runnable::run);
        } catch (IOException e) {
            log.error("Failed to reload config", e);
        }
    }

    /**
     * 注册配置变更监听器
     * @param listener 监听器回调
     */
    public void onConfigChanged(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Get config as JSON string (for API responses)
     * 将配置转换为JSON字符串（用于API响应）
     * @return JSON字符串
     */
    public String toJson() {
        try {
            return mapper.writeValueAsString(config);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Apply a partial JSON patch to config
     * 应用部分JSON补丁到配置
     * @param jsonPatch JSON补丁字符串
     * @throws IOException 解析失败时抛出
     */
    public void patch(String jsonPatch) throws IOException {
        mapper.readerForUpdating(config).readValue(jsonPatch);
        save();
        changeListeners.forEach(Runnable::run);
    }

    /**
     * Resolve env variable references like ${ENV_VAR}
     * 解析环境变量引用，如 ${ENV_VAR}
     * @param value 可能包含环境变量引用的值
     * @return 解析后的值
     */
    public String resolveSecret(String value) {
        if (value == null) return null;
        if (value.startsWith("${") && value.endsWith("}")) {
            String varName = value.substring(2, value.length() - 1);
            String envVal = System.getenv(varName);
            return envVal != null ? envVal : value;
        }
        return value;
    }
}
