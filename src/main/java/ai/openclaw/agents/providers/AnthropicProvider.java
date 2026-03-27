package ai.openclaw.agents.providers;

import ai.openclaw.agents.ChatMessage;
import ai.openclaw.agents.UsageStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 使用Messages API的Anthropic Claude提供者。
 */
public class AnthropicProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    /** 默认API基础URL */
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    /** Anthropic API版本 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

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
     * @param baseUrl    API基础URL（可为null使用默认值）
     * @param apiKey     API密钥
     */
    public AnthropicProvider(String providerId, String baseUrl, String apiKey) {
        this.providerId = providerId;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey = apiKey;
        this.mapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 获取提供者标识符。
     *
     * @return 提供者ID
     */
    @Override
    public String getProviderId() { return providerId; }

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
            .url(baseUrl + "/messages")
            .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .build();

        try (Response resp = httpClient.newCall(httpReq).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() != null ? resp.body().string() : "unknown";
                throw new RuntimeException("Anthropic API error " + resp.code() + ": " + errBody);
            }

            JsonNode json = mapper.readTree(resp.body().string());
            String content = json.path("content").path(0).path("text").asText();

            UsageStats usage = new UsageStats(
                json.path("usage").path("input_tokens").asInt(0),
                json.path("usage").path("output_tokens").asInt(0)
            );

            return new ChatCompletion(content, usage);
        }
    }

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
                .url(baseUrl + "/messages")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
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
                        onError.accept(new RuntimeException("Anthropic API error " + response.code() + ": " + errBody));
                    } catch (Exception e) {
                        onError.accept(e);
                    }
                    return;
                }

                StringBuilder fullContent = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            try {
                                JsonNode json = mapper.readTree(data);
                                String type = json.path("type").asText();
                                if ("content_block_delta".equals(type)) {
                                    // 内容增量块
                                    String delta = json.path("delta").path("text").asText();
                                    if (!delta.isEmpty()) {
                                        fullContent.append(delta);
                                        onChunk.accept(delta);
                                    }
                                } else if ("message_stop".equals(type)) {
                                    // 消息结束
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    onComplete.accept(new ChatCompletion(fullContent.toString(), new UsageStats()));
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        });
    }

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

        // 系统提示词
        if (req.systemPrompt != null && !req.systemPrompt.isBlank()) {
            body.put("system", req.systemPrompt);
        }

        // 消息（Anthropic Messages API中没有system角色）
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage msg : req.messages) {
            if (msg.role == ChatMessage.Role.system) continue;
            ObjectNode msgNode = messages.addObject();
            msgNode.put("role", msg.role.name());
            msgNode.put("content", msg.content != null ? msg.content : "");
        }

        return body;
    }
}