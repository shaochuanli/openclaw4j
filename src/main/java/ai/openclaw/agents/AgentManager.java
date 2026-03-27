package ai.openclaw.agents;

import ai.openclaw.agents.providers.*;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import ai.openclaw.gateway.GatewaySessionRegistry;
import ai.openclaw.skills.SkillManager;
import ai.openclaw.tools.SkillRegistry;
import ai.openclaw.tools.ToolCall;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolLoopDetector;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 核心Agent管理器：负责协调所有AI Agent的运行、提供者选择、会话管理
 * 以及多轮代理工具调用循环（ReAct模式）。
 */
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);
    /** 每个请求的最大工具调用轮次，用于避免无限循环 */
    private static final int MAX_TOOL_ROUNDS = 10;

    /** 每次运行的工具循环检测器：以sessionKey为键，每次运行后丢弃 */
    private final Map<String, ToolLoopDetector> loopDetectors = new ConcurrentHashMap<>();

    private final ConfigManager configManager;
    private final GatewaySessionRegistry sessionRegistry;
    private final ObjectMapper mapper;
    private final SessionManager sessionManager;
    private final SkillRegistry skillRegistry;
    private final ExecutorService executor;
    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeRuns = new ConcurrentHashMap<>();
    private final UsageStats globalUsage = new UsageStats();
    private final AtomicLong totalRequests = new AtomicLong(0);

    /**
     * SkillManager - 管理基于SKILL.md的知识技能（从商店或本地目录加载）
     * 可选：如果技能配置不存在/被禁用，则可能为null
     */
    private volatile SkillManager skillManager;

    // ─── 回调接口 ─────────────────────────────────────────────────

    /**
     * Agent运行回调接口。
     * 用于接收Agent运行过程中的各种事件通知。
     */
    public interface AgentCallback {
        /**
         * 接收流式输出的文本片段。
         *
         * @param chunk 文本片段
         */
        void onChunk(String chunk);

        /**
         * Agent运行完成时调用。
         *
         * @param fullResponse 完整响应文本
         * @param usage        Token使用统计
         */
        void onComplete(String fullResponse, UsageStats usage);

        /**
         * Agent运行出错时调用。
         *
         * @param error 错误信息
         */
        void onError(String error);

        /**
         * 当Agent即将执行工具时调用。
         *
         * @param toolName  工具名称
         * @param arguments 工具参数（JSON字符串）
         */
        default void onToolCall(String toolName, String arguments) {}

        /**
         * 工具执行完成后调用。
         *
         * @param toolName 工具名称
         * @param result   执行结果
         * @param success  是否执行成功
         */
        default void onToolResult(String toolName, String result, boolean success) {}
    }

    /**
     * Agent运行结果。
     * 包含响应文本和Token使用统计。
     */
    public static class AgentRunResult {
        /** 响应文本 */
        public String response;
        /** Token使用统计 */
        public UsageStats usage;

        /**
         * 构造函数。
         *
         * @param response 响应文本
         * @param usage    Token使用统计
         */
        public AgentRunResult(String response, UsageStats usage) {
            this.response = response;
            this.usage = usage;
        }
    }

    // ─── 生命周期 ────────────────────────────────────────────────────────────

    /** 构造函数，初始化AgentManager */
    public AgentManager(ConfigManager configManager, GatewaySessionRegistry sessionRegistry, ObjectMapper mapper) {
        this.configManager = configManager;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
        this.sessionManager = new SessionManager(configManager, mapper);
        this.skillRegistry = new SkillRegistry();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动AgentManager，初始化提供商和SkillManager
     */
    public void start() {
        buildProviders();

        // 初始化SkillManager - 始终使用config.skills或默认值
        ai.openclaw.skills.SkillsConfig skillsCfg = configManager.getConfig().skills;
        if (skillsCfg == null) {
            skillsCfg = new ai.openclaw.skills.SkillsConfig(); // 使用所有默认值
        }
        skillManager = new SkillManager(skillsCfg, mapper, null);
        skillManager.start();

        log.info("AgentManager已启动，{}个提供商，{}个工具，skillManager={}",
            providers.size(), skillRegistry.getAllTools().size(),
            skillManager != null ? skillManager.getAllSkills().size() + "个技能" : "已禁用");
    }

    /**
     * 停止AgentManager，关闭SkillManager和线程池
     */
    public void stop() {
        if (skillManager != null) skillManager.stop();
        executor.shutdownNow();
    }

    /**
     * 注入或替换SkillManager。
     * 如果尚未启动，则同时启动管理器。
     */
    public void setSkillManager(SkillManager skillManager) {
        if (this.skillManager != null) this.skillManager.stop();
        this.skillManager = skillManager;
    }

    /**
     * 注册动态工具（运行时注入的工具）。
     *
     * @param tool 要注册的工具
     */
    public void registerDynamicTool(ai.openclaw.tools.Tool tool) {
        skillRegistry.registerDynamicTool(tool);
    }

    // ─── 公共运行方法 ───────────────────────────────────────────────────

    /**
     * 异步运行Agent，支持流式输出
     */
    public void runAsync(String agentId, String sessionKey, String userMessage, AgentCallback callback) {
        Future<?> future = executor.submit(() -> {
            try {
                runInternal(agentId, sessionKey, userMessage, true, callback);
            } catch (Exception e) {
                log.error("Agent运行失败", e);
                callback.onError(e.getMessage());
            }
        });
        activeRuns.put(sessionKey, future);
    }

    /**
     * 同步运行Agent（用于REST API调用）
     */
    public AgentRunResult runSync(String agentId, String sessionKey, String userMessage) throws Exception {
        CompletableFuture<AgentRunResult> resultFuture = new CompletableFuture<>();

        executor.submit(() -> {
            try {
                runInternal(agentId, sessionKey, userMessage, false, new AgentCallback() {
                    @Override public void onChunk(String chunk) {}
                    @Override public void onComplete(String fullResponse, UsageStats usage) {
                        resultFuture.complete(new AgentRunResult(fullResponse, usage));
                    }
                    @Override public void onError(String error) {
                        resultFuture.completeExceptionally(new RuntimeException(error));
                    }
                });
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        });

        return resultFuture.get(120, TimeUnit.SECONDS);
    }

    /**
     * 中止指定会话的正在运行的Agent
     */
    public void abort(String sessionKey) {
        Future<?> future = activeRuns.remove(sessionKey);
        if (future != null) {
            future.cancel(true);
            log.info("已中止会话的Agent运行: {}", sessionKey);
        }
    }

    // ─── 核心运行循环 ────────────────────────────────────────────────────────

    /**
     * 内部运行方法，处理Agent的核心执行逻辑。
     * 实现多轮工具调用的ReAct循环。
     *
     * @param agentId     Agent标识符
     * @param sessionKey  会话标识符
     * @param userMessage 用户消息
     * @param streaming   是否使用流式输出
     * @param callback    回调接口
     * @throws Exception 执行过程中可能抛出的异常
     */
    private void runInternal(String agentId, String sessionKey, String userMessage,
                             boolean streaming, AgentCallback callback) throws Exception {
        totalRequests.incrementAndGet();

        // 解析Agent配置
        OpenClaw4jConfig.AgentConfig agentConfig = resolveAgent(agentId);
        if (agentConfig == null) {
            callback.onError("Agent not found: " + agentId);
            return;
        }

        // 处理重置命令
        String trimmed = userMessage.trim();
        String resetCommand = configManager.getConfig().session.resetCommand;
        if (trimmed.equals(resetCommand) || trimmed.equals("/reset")) {
            sessionManager.resetSession(sessionKey);
            callback.onComplete("Session reset. Starting fresh conversation.", new UsageStats());
            return;
        }

        // 获取会话历史
        List<ChatMessage> history = sessionManager.getOrCreate(sessionKey);

        // 添加用户消息到历史
        ChatMessage userMsg = new ChatMessage(ChatMessage.Role.user, userMessage);
        sessionManager.addMessage(sessionKey, userMsg);

        // 解析模型
        String[] modelParts = resolveModelRef(agentConfig.model);
        if (modelParts == null) {
            callback.onError("Model not found: " + agentConfig.model);
            return;
        }
        String providerId = modelParts[0];
        String modelId = modelParts[1];

        ModelProvider provider = providers.get(providerId);
        if (provider == null) {
            callback.onError("Provider not configured: " + providerId + ". Please check your config.");
            return;
        }

        // 为此Agent解析工具
        List<ToolDefinition> tools = skillRegistry.resolveSkills(agentConfig.skills);
        log.info("[Agent] id={} model={}/{} skills={} tools={} knowledgeSkills={}",
            agentId, providerId, modelId,
            agentConfig.skills,
            tools.stream().map(t -> t.name).toList(),
            skillManager != null ? skillManager.getEnabledSkills().size() : 0);

        // ── 代理循环 ─────────────────────────────────────────────────────
        // 构建初始请求
        ModelProvider.CompletionRequest req = buildRequest(modelId, sessionKey, agentConfig, tools);

        // 为本次运行创建新的ToolLoopDetector
        ToolLoopDetector loopDetector = new ToolLoopDetector();
        loopDetectors.put(sessionKey, loopDetector);

        UsageStats totalUsage = new UsageStats();
        int round = 0;

        while (round < MAX_TOOL_ROUNDS) {
            round++;
            final boolean isLastRound = (round >= MAX_TOOL_ROUNDS);
            log.info("[AgentLoop] round={} streaming={} tools={}", round, streaming, tools.size());

            if (streaming) {
                // ── 流式模式 ────────────────────────────────────────────
                CompletableFuture<ModelProvider.ChatCompletion> completionFuture = new CompletableFuture<>();

                provider.streamComplete(req,
                    chunk -> callback.onChunk(chunk),
                    completion -> completionFuture.complete(completion),
                    err -> completionFuture.completeExceptionally(err)
                );

                ModelProvider.ChatCompletion completion;
                try {
                    completion = completionFuture.get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                    activeRuns.remove(sessionKey);
                    return;
                }

                log.info("[AgentLoop] round={} finish_reason={} hasToolCalls={} contentLen={}",
                    round, completion.finishReason, completion.hasToolCalls(),
                    completion.content != null ? completion.content.length() : 0);

                totalUsage.add(completion.usage);
                globalUsage.add(completion.usage);

                // 检查工具调用
                if (completion.hasToolCalls() && !isLastRound) {
                    // 保存带有tool_calls的助手消息到历史
                    saveAssistantToolCallMessage(sessionKey, completion);

                    // 执行所有工具调用并添加结果到历史
                    boolean shouldContinue = executeToolCalls(completion.toolCalls, sessionKey, callback);
                    if (!shouldContinue) break;

                    // 使用更新后的历史为下一轮重建请求
                    req = buildRequest(modelId, sessionKey, agentConfig, tools);
                    continue;
                }

                // 最终文本响应 - 保存到历史
                String finalContent = completion.content != null ? completion.content : "";
                if (!finalContent.isBlank()) {
                    ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.assistant, finalContent);
                    assistantMsg.usage = completion.usage;
                    sessionManager.addMessage(sessionKey, assistantMsg);
                }
                activeRuns.remove(sessionKey);
                loopDetectors.remove(sessionKey);
                callback.onComplete(finalContent, totalUsage);
                return;

            } else {
                // ── 非流式模式 ────────────────────────────────────────
                ModelProvider.ChatCompletion completion = provider.complete(req);
                totalUsage.add(completion.usage);
                globalUsage.add(completion.usage);

                if (completion.hasToolCalls() && !isLastRound) {
                    saveAssistantToolCallMessage(sessionKey, completion);
                    boolean shouldContinue = executeToolCalls(completion.toolCalls, sessionKey, callback);
                    if (!shouldContinue) break;
                    req = buildRequest(modelId, sessionKey, agentConfig, tools);
                    continue;
                }

                // 最终响应
                String finalContent = completion.content != null ? completion.content : "";
                ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.assistant, finalContent);
                assistantMsg.usage = completion.usage;
                sessionManager.addMessage(sessionKey, assistantMsg);
                loopDetectors.remove(sessionKey);
                callback.onComplete(finalContent, totalUsage);
                return;
            }
        }

        // 达到最大轮次 - 返回已有的结果
        log.warn("Agent '{}'达到最大工具调用轮次 ({})", agentId, MAX_TOOL_ROUNDS);
        loopDetectors.remove(sessionKey);
        callback.onError("Max tool-call rounds reached. The agent may be in a loop.");
        activeRuns.remove(sessionKey);
    }

    // ─── 工具执行 ───────────────────────────────────────────────────────

    /**
     * 执行工具调用列表，并将结果保存到会话历史。
     * 集成ToolLoopDetector（精确重复+高频循环检测）和before_tool_call钩子扩展点。
     *
     * @return true 继续循环，false 停止
     */
    private boolean executeToolCalls(List<ToolCall> toolCalls, String sessionKey, AgentCallback callback) {
        ToolLoopDetector detector = loopDetectors.getOrDefault(sessionKey, new ToolLoopDetector());

        for (ToolCall tc : toolCalls) {
            String toolName = tc.name != null ? tc.name : "tool";
            String argsJson = tc.arguments != null ? tc.arguments : "{}";

            // ── before_tool_call: 循环检测 ─────────────────────────────
            ToolLoopDetector.DetectResult loopResult = detector.detect(toolName, argsJson);
            if (loopResult.isBlocking()) {
                log.error("[ToolLoop] Blocking tool call '{}': {}", toolName, loopResult.message);
                // 将阻止结果报告给会话，以便模型知道为何停止
                ChatMessage blockMsg = ChatMessage.toolResult(
                    tc.id != null ? tc.id : "blocked",
                    toolName,
                    "ERROR: " + loopResult.message
                );
                sessionManager.addMessage(sessionKey, blockMsg);
                callback.onToolResult(toolName, loopResult.message, false);
                // 停止代理循环 - 这是一个关键阻止
                return false;
            }
            if (loopResult.isWarning()) {
                log.warn("[ToolLoop] Warning for tool '{}': {}", toolName, loopResult.message);
                // 继续但让回调知道（UI可以向用户显示）
                callback.onToolCall(toolName + " [LOOP-WARN: " + loopResult.message + "]", argsJson);
            } else {
                log.info("[AgentLoop] Tool call: {} args={}", toolName, argsJson);
                callback.onToolCall(toolName, argsJson);
            }

            // ── 解析参数 ───────────────────────────────────────────────
            JsonNode argsNode;
            try {
                argsNode = mapper.readTree(argsJson);
            } catch (Exception e) {
                argsNode = mapper.createObjectNode();
            }

            // ── 执行工具 ──────────────────────────────────────────────────
            ToolResult result = skillRegistry.executeTool(toolName, tc.id, argsNode);
            callback.onToolResult(toolName, result.content, result.success);

            // 保存工具结果到会话历史
            ChatMessage toolMsg = ChatMessage.toolResult(tc.id, toolName, result.content);
            sessionManager.addMessage(sessionKey, toolMsg);
        }
        return true;
    }

    /**
     * 保存包含tool_calls的助手消息到历史记录。
     * 以便在下一轮重新发送给模型，保持对话上下文完整性。
     *
     * @param sessionKey 会话标识符
     * @param completion 包含工具调用信息的补全结果
     */
    private void saveAssistantToolCallMessage(String sessionKey, ModelProvider.ChatCompletion completion) {
        try {
            // 将tool_calls序列化为JSON字符串以便重新嵌入
            ArrayNode toolCallsArray = mapper.createArrayNode();
            for (ToolCall tc : completion.toolCalls) {
                ObjectNode tcNode = mapper.createObjectNode();
                tcNode.put("id", tc.id != null ? tc.id : ("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)));
                tcNode.put("type", "function");
                ObjectNode fn = tcNode.putObject("function");
                fn.put("name", tc.name);
                fn.put("arguments", tc.arguments != null ? tc.arguments : "{}");
                toolCallsArray.add(tcNode);
            }

            ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.assistant,
                completion.content != null ? completion.content : "");
            assistantMsg.rawToolCalls = mapper.writeValueAsString(toolCallsArray);
            sessionManager.addMessage(sessionKey, assistantMsg);
        } catch (Exception e) {
            log.error("保存助手tool_calls消息失败", e);
        }
    }

    // ─── 请求构建器 ──────────────────────────────────────────────────────

    /**
     * 为给定的Agent和会话状态构建CompletionRequest。
     *
     * <p>系统提示词组装顺序：
     * <ol>
     *   <li>Agent自身的systemPrompt（基础指令）</li>
     *   <li>技能块 - 每个启用的知识技能的SKILL.md内容，
     *       由{@link SkillManager#buildSkillsPromptBlock(List)}追加</li>
     * </ol>
     *
     * <p>这允许安装的技能（如来自SkillHub）将领域知识和使用说明
     * 直接注入模型上下文，无需修改代码。
     */
    private ModelProvider.CompletionRequest buildRequest(String modelId, String sessionKey,
            OpenClaw4jConfig.AgentConfig agentConfig, List<ToolDefinition> tools) {

        // ── 1. 基础系统提示词 ─────────────────────────────────────────────
        String basePrompt = agentConfig.systemPrompt != null ? agentConfig.systemPrompt : "";

        // ── 2. 追加SkillManager技能块 ─────────────────────────
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(basePrompt, agentConfig.skills);

        // ── 3. 组装请求 ───────────────────────────────────────────────
        ModelProvider.CompletionRequest req = new ModelProvider.CompletionRequest(
            modelId,
            new ArrayList<>(sessionManager.getOrCreate(sessionKey))
        );
        req.systemPrompt = effectiveSystemPrompt.isBlank() ? null : effectiveSystemPrompt;
        req.temperature = agentConfig.temperature;
        req.maxTokens = agentConfig.maxTokens;
        req.tools = tools;
        return req;
    }

    /**
     * 通过将SkillManager技能块（所有启用的适用技能的SKILL.md内容）
     * 追加到Agent的基础提示词来组合有效的系统提示词。
     *
     * <p>如果skillManager为null或产生空块，则原样返回基础提示词。
     *
     * @param basePrompt   Agent自身的系统提示词（可为空/null）
     * @param agentSkills  为此Agent配置的技能键列表
     *                     （null / 空 / ["all"] → 包含所有启用的技能）
     * @return 完整组装的系统提示词字符串
     */
    private String buildEffectiveSystemPrompt(String basePrompt, List<String> agentSkills) {
        if (skillManager == null) return basePrompt != null ? basePrompt : "";

        String skillsBlock = skillManager.buildSkillsPromptBlock(agentSkills);
        if (skillsBlock == null || skillsBlock.isBlank()) {
            return basePrompt != null ? basePrompt : "";
        }

        String base = basePrompt != null ? basePrompt : "";
        String combined = base + skillsBlock;

        log.debug("[Skills] Injected {} chars of skills content into system prompt (agent skills: {})",
            skillsBlock.length(), agentSkills);

        return combined;
    }

    // ─── 提供商/模型解析 ────────────────────────────────────────────

    /**
     * 根据配置构建LLM提供商
     */
    private void buildProviders() {
        providers.clear();
        configManager.getConfig().models.providers.forEach((id, providerCfg) -> {
            String apiKey = configManager.resolveSecret(providerCfg.apiKey);
            ModelProvider provider = switch (providerCfg.api) {
                case "openai-completions", "openai" ->
                    new OpenAIProvider(id, providerCfg.baseUrl, apiKey);
                case "anthropic-messages", "anthropic" ->
                    new AnthropicProvider(id, providerCfg.baseUrl, apiKey);
                case "ollama" ->
                    new OllamaProvider(id, providerCfg.baseUrl);
                default -> {
                    log.warn("未知的提供商API类型 '{}' for 提供商 '{}'，默认使用OpenAI兼容模式", providerCfg.api, id);
                    yield new OpenAIProvider(id, providerCfg.baseUrl, apiKey);
                }
            };
            providers.put(id, provider);
            log.info("已注册提供商: {} ({})", id, providerCfg.api);
        });
    }

    /**
     * 解析模型引用，返回[providerId, modelId]数组
     */
    private String[] resolveModelRef(String modelRef) {
        String[] parts = modelRef.split("/", 2);
        if (parts.length == 2) return parts;
        // 尝试别名解析
        for (Map.Entry<String, OpenClaw4jConfig.ModelProviderConfig> entry
                : configManager.getConfig().models.providers.entrySet()) {
            for (var model : entry.getValue().models) {
                if (modelRef.equals(model.id)) return new String[]{entry.getKey(), model.id};
            }
        }
        return null;
    }

    /**
     * 解析Agent ID，返回对应的Agent配置
     */
    private OpenClaw4jConfig.AgentConfig resolveAgent(String agentId) {
        return configManager.getConfig().agents.agents.stream()
            .filter(a -> agentId.equals(a.id))
            .findFirst()
            .orElse(configManager.getConfig().agents.agents.isEmpty() ? null
                : configManager.getConfig().agents.agents.get(0));
    }

    // ─── 访问器 ────────────────────────────────────────────────────────────

    /**
     * 获取会话管理器。
     *
     * @return 会话管理器实例
     */
    public SessionManager getSessionManager() { return sessionManager; }

    /**
     * 获取技能注册表。
     *
     * @return 技能注册表实例
     */
    public SkillRegistry getSkillRegistry() { return skillRegistry; }

    /**
     * 获取技能管理器。
     *
     * @return 技能管理器实例，如果未配置则返回null
     */
    public SkillManager getSkillManager() { return skillManager; }

    /**
     * 列出所有可用的模型。
     *
     * @return 模型信息列表，每个元素包含id、name、provider、modelId、available字段
     */
    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> result = new ArrayList<>();
        configManager.getConfig().models.providers.forEach((pid, pCfg) -> {
            for (var model : pCfg.models) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", pid + "/" + model.id);
                m.put("name", model.name);
                m.put("provider", pid);
                m.put("modelId", model.id);
                m.put("available", providers.containsKey(pid));
                result.add(m);
            }
        });
        return result;
    }

    /**
     * 获取全局使用统计信息。
     *
     * @return 包含totalRequests、inputTokens、outputTokens、totalTokens、activeRuns的统计信息
     */
    public Map<String, Object> getUsageStats() {
        return Map.of(
            "totalRequests", totalRequests.get(),
            "inputTokens", globalUsage.inputTokens,
            "outputTokens", globalUsage.outputTokens,
            "totalTokens", globalUsage.totalTokens,
            "activeRuns", activeRuns.size()
        );
    }
}
