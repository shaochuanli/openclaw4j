package ai.openclaw.agents.providers;

import ai.openclaw.agents.ChatMessage;
import ai.openclaw.agents.UsageStats;
import ai.openclaw.tools.ToolCall;
import ai.openclaw.tools.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI兼容的补全提供者。
 * 适用于：OpenAI、Azure OpenAI、DashScope（通义千问）、本地OpenAI兼容服务器等。
 * 通过OpenAI tool_calls API支持函数/工具调用。
 */
public class OpenAIProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);

    /** 提供者标识符 */
    private final String providerId;
    /** API基础URL */
    private final String baseUrl;
    /** API密钥 */
    private final String apiKey;
    /** JSON序列化工具 */
    private final ObjectMapper mapper;
    /** HTTP客户端 */
    private final OkHttpClient httpClient;

    /**
     * 构造函数。
     *
     * @param providerId 提供者标识符
     * @param baseUrl    API基础URL
     * @param apiKey     API密钥
     */
    public OpenAIProvider(String providerId, String baseUrl, String apiKey) {
        this.providerId = providerId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.mapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 获取提供者标识符。
     *
     * @return 提供者ID
     */
    @Override
    public String getProviderId() { return providerId; }

    // ─── 非流式 ────────────────────────────────────────────────────────────────

    /**
     * 发送非流式补全请求。
     *
     * @param req 补全请求
     * @return 补全结果
     * @throws Exception 请求失败时抛出异常
     */
    @Override
    public ChatCompletion complete(CompletionRequest req) throws Exception {
        ObjectNode body = buildRequestBody(req, false);

        Request httpReq = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .build();

        try (Response resp = httpClient.newCall(httpReq).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() != null ? resp.body().string() : "unknown";
                throw new RuntimeException("OpenAI API error " + resp.code() + ": " + errBody);
            }

            JsonNode json = mapper.readTree(resp.body().string());
            JsonNode choice = json.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");

            JsonNode messageNode = choice.path("message");
            JsonNode contentNode = messageNode.path("content");
            String content = (contentNode.isNull() || contentNode.isMissingNode()) ? "" : contentNode.asText();

            UsageStats usage = new UsageStats(
                json.path("usage").path("prompt_tokens").asInt(0),
                json.path("usage").path("completion_tokens").asInt(0)
            );

            ChatCompletion completion = new ChatCompletion(content, usage);
            completion.finishReason = finishReason;

            // 如果存在工具调用则解析
            if ("tool_calls".equals(finishReason) || messageNode.has("tool_calls")) {
                completion.toolCalls = parseToolCalls(messageNode.path("tool_calls"));
            }

            return completion;
        }
    }

    // ─── 流式 ────────────────────────────────────────────────────────────────

    /**
     * 发送流式补全请求。
     *
     * @param req        补全请求
     * @param onChunk    接收文本增量的回调
     * @param onComplete 接收最终结果的回调
     * @param onError    接收错误的回调
     */
    @Override
    public void streamComplete(CompletionRequest req, Consumer<String> onChunk,
                               Consumer<ChatCompletion> onComplete, Consumer<Exception> onError) {
        ObjectNode body = buildRequestBody(req, true);

        Request httpReq;
        try {
            httpReq = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
        } catch (Exception e) {
            onError.accept(e);
            return;
        }

        httpClient.newCall(httpReq).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                onError.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    try {
                        String errBody = response.body() != null ? response.body().string() : "unknown";
                        onError.accept(new RuntimeException("OpenAI API error " + response.code() + ": " + errBody));
                    } catch (Exception e) {
                        onError.accept(e);
                    }
                    return;
                }

                StringBuilder fullContent = new StringBuilder();
                // 流式tool_calls处理：按索引累积调用片段
                // 映射：索引 -> {id, name, arguments_builder}
                Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
                String[] lastFinishReason = {null};

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), "UTF-8"))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        if (data.isBlank()) continue;

                        try {
                            JsonNode json = mapper.readTree(data);
                            JsonNode choice = json.path("choices").path(0);

                            // 捕获完成原因
                            String fr = choice.path("finish_reason").asText(null);
                            if (fr != null && !fr.equals("null")) lastFinishReason[0] = fr;

                            JsonNode deltaNode = choice.path("delta");

                            // 处理流中的tool_calls
                            JsonNode toolCallsNode = deltaNode.path("tool_calls");
                            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray()) {
                                for (JsonNode tc : toolCallsNode) {
                                    int idx = tc.path("index").asInt(0);
                                    ToolCallAccumulator acc = toolCallAccumulators.computeIfAbsent(idx, k -> new ToolCallAccumulator());
                                    if (tc.has("id")) acc.id = tc.path("id").asText();
                                    JsonNode fnNode = tc.path("function");
                                    if (fnNode.has("name")) acc.name = fnNode.path("name").asText();
                                    if (fnNode.has("arguments")) acc.args.append(fnNode.path("arguments").asText());
                                }
                                continue; // tool_calls块 – 无内容需要发送
                            }

                            // 处理普通内容块
                            JsonNode contentNode = deltaNode.path("content");
                            // 跳过null节点（Qwen3思考阶段会返回JSON null作为内容）
                            if (contentNode.isNull() || contentNode.isMissingNode()) continue;
                            String delta = contentNode.asText();
                            if (!delta.isEmpty()) {
                                fullContent.append(delta);
                                onChunk.accept(delta);
                            }
                        } catch (Exception ignored) {}
                    }

                    // 构建最终的ChatCompletion
                    ChatCompletion completion = new ChatCompletion(fullContent.toString(), new UsageStats());
                    completion.finishReason = lastFinishReason[0];
                    log.info("[OpenAI] Stream done. finish_reason={} toolAccumulators={} contentLen={}",
                        lastFinishReason[0], toolCallAccumulators.size(), fullContent.length());

                    // 转换累积的工具调用
                    if (!toolCallAccumulators.isEmpty()) {
                        List<ToolCall> toolCalls = new ArrayList<>();
                        for (int i = 0; i < toolCallAccumulators.size(); i++) {
                            ToolCallAccumulator acc = toolCallAccumulators.get(i);
                            if (acc != null && acc.name != null) {
                                toolCalls.add(new ToolCall(acc.id, acc.name, acc.args.toString()));
                            }
                        }
                        if (!toolCalls.isEmpty()) {
                            completion.toolCalls = toolCalls;
                        }
                    }

                    onComplete.accept(completion);
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        });
    }

    // ─── 请求构建器 ────────────────────────────────────────────────────────────────

    /**
     * 构建API请求体。
     *
     * @param req    补全请求
     * @param stream 是否流式
     * @return JSON请求体
     */
    private ObjectNode buildRequestBody(CompletionRequest req, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", req.modelId);
        body.put("stream", stream);
        body.put("max_tokens", req.maxTokens);
        body.put("temperature", req.temperature);

        // 禁用Qwen3思考模式以避免推理阶段的null内容块。
        // 重要：enable_thinking必须始终为false — 无论普通聊天还是tool_calls模式。
        // 使用tool_calls时，Qwen在推理期间返回null内容增量，这会破坏流式tool_call解析。
        // 此标志会被非Qwen提供者静默忽略。
        body.put("enable_thinking", false);

        // 消息
        ArrayNode messages = body.putArray("messages");

        // 如果存在系统提示词则添加
        if (req.systemPrompt != null && !req.systemPrompt.isBlank()) {
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", req.systemPrompt);
        }

        // 添加对话历史（包括工具消息）
        for (ChatMessage msg : req.messages) {
            if (msg.role == ChatMessage.Role.system) continue; // 已处理
            appendMessageToArray(messages, msg);
        }

        // 工具
        if (req.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition td : req.tools) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("type", "function");
                ObjectNode fn = toolNode.putObject("function");
                fn.put("name", td.name);
                fn.put("description", td.description);
                fn.set("parameters", td.parameters);
            }
            // 让模型决定何时调用工具
            body.put("tool_choice", "auto");
            log.info("[OpenAI] Request with {} tools, model={}", req.tools.size(), req.modelId);
        }

        return body;
    }

    /**
     * 将ChatMessage追加到消息数组。
     * 特殊处理工具消息和包含tool_calls的助手消息。
     *
     * @param messages 消息数组
     * @param msg      要追加的消息
     */
    private void appendMessageToArray(ArrayNode messages, ChatMessage msg) {
        ObjectNode msgNode = messages.addObject();

        if (msg.role == ChatMessage.Role.tool) {
            // 工具结果消息
            msgNode.put("role", "tool");
            msgNode.put("tool_call_id", msg.toolCallId != null ? msg.toolCallId : "");
            msgNode.put("content", msg.content != null ? msg.content : "");
        } else if (msg.role == ChatMessage.Role.assistant && msg.rawToolCalls != null) {
            // 请求工具调用的助手消息
            msgNode.put("role", "assistant");
            if (msg.content != null && !msg.content.isBlank()) {
                msgNode.put("content", msg.content);
            } else {
                msgNode.putNull("content");
            }
            // 重新嵌入tool_calls数组
            try {
                JsonNode toolCallsJson = mapper.readTree(msg.rawToolCalls);
                msgNode.set("tool_calls", toolCallsJson);
            } catch (Exception e) {
                log.warn("重新序列化历史记录中的tool_calls失败: {}", e.getMessage());
            }
        } else {
            msgNode.put("role", msg.role.name());
            msgNode.put("content", msg.content != null ? msg.content : "");
        }
    }

    // ─── 工具调用解析 ────────────────────────────────────────────────────────────────

    /**
     * 解析工具调用响应。
     *
     * @param toolCallsNode 工具调用JSON节点
     * @return 工具调用列表
     */
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> result = new ArrayList<>();
        if (toolCallsNode == null || toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) return result;

        for (JsonNode tc : toolCallsNode) {
            String id = tc.path("id").asText("");
            String name = tc.path("function").path("name").asText("");
            String args = tc.path("function").path("arguments").asText("{}");
            if (!name.isEmpty()) result.add(new ToolCall(id, name, args));
        }
        return result;
    }

    /** 按索引累积流式工具调用片段 */
    private static class ToolCallAccumulator {
        /** 工具调用ID */
        String id;
        /** 工具名称 */
        String name;
        /** 参数JSON字符串构建器 */
        StringBuilder args = new StringBuilder();
    }
}