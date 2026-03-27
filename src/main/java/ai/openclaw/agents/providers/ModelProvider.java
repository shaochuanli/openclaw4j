package ai.openclaw.agents.providers;

import ai.openclaw.agents.ChatMessage;
import ai.openclaw.agents.UsageStats;
import ai.openclaw.tools.ToolCall;
import ai.openclaw.tools.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 不同LLM提供者（OpenAI、Anthropic、Ollama等）的抽象接口。
 */
public interface ModelProvider {

    /**
     * 获取提供者标识符。
     *
     * @return 提供者ID
     */
    String getProviderId();

    /**
     * 发送聊天补全请求（非流式）。
     *
     * @param request 补全请求
     * @return 补全结果
     * @throws Exception 请求失败时抛出异常
     */
    ChatCompletion complete(CompletionRequest request) throws Exception;

    /**
     * 发送流式聊天补全请求。
     * onChunk 接收文本增量块。
     * onComplete 接收最终的ChatCompletion（如果finish_reason=tool_calls则可能包含tool_calls）。
     *
     * @param request    补全请求
     * @param onChunk    接收文本增量的回调
     * @param onComplete 接收最终结果的回调
     * @param onError    接收错误的回调
     */
    void streamComplete(CompletionRequest request, Consumer<String> onChunk,
                        Consumer<ChatCompletion> onComplete, Consumer<Exception> onError);

    // ─── 请求/响应 ────────────────────────────────────────────────────────────────

    /** 补全请求类 */
    class CompletionRequest {
        /** 模型ID */
        public String modelId;
        /** 消息历史 */
        public List<ChatMessage> messages;
        /** 温度参数（控制随机性）*/
        public double temperature = 0.7;
        /** 最大输出token数 */
        public int maxTokens = 4096;
        /** 系统提示词 */
        public String systemPrompt;
        /** 模型可用的工具列表。空列表表示不使用工具。 */
        public List<ToolDefinition> tools = new ArrayList<>();

        /**
         * 构造函数。
         *
         * @param modelId  模型ID
         * @param messages 消息历史
         */
        public CompletionRequest(String modelId, List<ChatMessage> messages) {
            this.modelId = modelId;
            this.messages = messages;
        }

        /**
         * 检查是否有可用工具。
         *
         * @return 如果有工具返回true
         */
        public boolean hasTools() {
            return tools != null && !tools.isEmpty();
        }
    }

    /** 聊天补全结果类 */
    class ChatCompletion {
        /** 响应内容 */
        public String content;
        /** Token使用统计 */
        public UsageStats usage;
        /** 完成原因（如"stop"、"tool_calls"）*/
        public String finishReason;
        /** 当模型想要调用工具时非空（finish_reason = "tool_calls"） */
        public List<ToolCall> toolCalls;

        /**
         * 构造函数。
         *
         * @param content 响应内容
         * @param usage   Token使用统计
         */
        public ChatCompletion(String content, UsageStats usage) {
            this.content = content;
            this.usage = usage != null ? usage : new UsageStats();
        }

        /**
         * 检查是否有工具调用。
         *
         * @return 如果有工具调用返回true
         */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}