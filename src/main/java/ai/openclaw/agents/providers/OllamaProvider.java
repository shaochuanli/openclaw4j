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
 * 本地模型的Ollama提供者。
 * 使用Ollama的/api/chat端点（也支持OpenAI兼容模式）。
 */
public class OllamaProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    /** 提供者标识符 */
    private final String providerId;
    /** Ollama服务URL */
    private final String baseUrl;
    /** JSON序列化工具 */
    private final ObjectMapper mapper;
    /** HTTP客户端 */
    private final OkHttpClient httpClient;

    /**
     * 构造函数。
     *
     * @param providerId 提供者标识符
     * @param baseUrl    Ollama服务URL（可为null使用默认值localhost:11434）
     */
    public OllamaProvider(String providerId, String baseUrl) {
        this.providerId = providerId;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "http://localhost:11434";
        this.mapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
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
            .url(baseUrl + "/api/chat")
            .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
            .build();

        try (Response resp = httpClient.newCall(httpReq).execute()) {
            if (!resp.isSuccessful()) {
                throw new RuntimeException("Ollama error " + resp.code());
            }
            JsonNode json = mapper.readTree(resp.body().string());
            String content = json.path("message").path("content").asText();
            return new ChatCompletion(content, new UsageStats(
                json.path("prompt_eval_count").asInt(0),
                json.path("eval_count").asInt(0)
            ));
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
                .url(baseUrl + "/api/chat")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.parse("application/json")))
                .build();
        } catch (Exception e) {
            onError.accept(e);
            return;
        }

        httpClient.newCall(httpReq).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) { onError.accept(e); }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    onError.accept(new RuntimeException("Ollama error " + response.code()));
                    return;
                }

                StringBuilder fullContent = new StringBuilder();
                int inputTokens = 0;
                int outputTokens = 0;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        try {
                            JsonNode json = mapper.readTree(line);
                            String content = json.path("message").path("content").asText();
                            if (!content.isEmpty()) {
                                fullContent.append(content);
                                onChunk.accept(content);
                            }
                            // 检查是否完成
                            boolean done = json.path("done").asBoolean(false);
                            if (done) {
                                // 获取token统计
                                inputTokens = json.path("prompt_eval_count").asInt(0);
                                outputTokens = json.path("eval_count").asInt(0);
                                break;
                            }
                        } catch (Exception ignored) {}
                    }

                    onComplete.accept(new ChatCompletion(fullContent.toString(),
                        new UsageStats(inputTokens, outputTokens)));
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

        ArrayNode messages = body.putArray("messages");

        // 系统提示词
        if (req.systemPrompt != null && !req.systemPrompt.isBlank()) {
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", req.systemPrompt);
        }

        // 添加消息历史
        for (ChatMessage msg : req.messages) {
            if (msg.role == ChatMessage.Role.system) continue;
            ObjectNode msgNode = messages.addObject();
            msgNode.put("role", msg.role.name());
            msgNode.put("content", msg.content != null ? msg.content : "");
        }

        // Ollama选项
        ObjectNode options = body.putObject("options");
        options.put("temperature", req.temperature);
        options.put("num_predict", req.maxTokens);

        return body;
    }
}