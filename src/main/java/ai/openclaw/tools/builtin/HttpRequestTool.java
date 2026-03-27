package ai.openclaw.tools.builtin;

import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

/**
 * 工具：http_request
 * 发送HTTP请求并返回响应。
 */
public class HttpRequestTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 最大响应字符数 */
    private static final int MAX_RESPONSE_CHARS = 10000;
    /** HTTP客户端 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /**
     * 获取工具定义。
     *
     * @return HTTP请求工具的定义，包含URL、方法、请求体、请求头等参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode url = MAPPER.createObjectNode();
        url.put("type", "string");
        url.put("description", "请求的URL");
        props.set("url", url);

        ObjectNode method = MAPPER.createObjectNode();
        method.put("type", "string");
        method.put("description", "HTTP方法：GET、POST、PUT、DELETE等。默认：GET");
        method.putArray("enum").add("GET").add("POST").add("PUT").add("DELETE").add("PATCH").add("HEAD");
        props.set("method", method);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "string");
        body.put("description", "请求体（用于POST/PUT/PATCH）");
        props.set("body", body);

        ObjectNode headers = MAPPER.createObjectNode();
        headers.put("type", "object");
        headers.put("description", "额外的HTTP头，作为键值对");
        props.set("headers", headers);

        params.set("properties", props);
        params.putArray("required").add("url");

        return new ToolDefinition(
            "http_request",
            "向URL发送HTTP请求并返回状态码和响应体。" +
            "用于调用REST API、获取网页等。",
            params
        );
    }

    /**
     * 执行HTTP请求。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，包含url、method、body、headers
     * @return 包含HTTP状态码和响应体的执行结果
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        String url = args.path("url").asText("").trim();
        String method = args.path("method").asText("GET").toUpperCase();
        String bodyStr = args.path("body").asText(null);

        if (url.isEmpty()) {
            return ToolResult.error(toolCallId, "http_request", "url is required");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30));

            // 添加请求头
            JsonNode headers = args.path("headers");
            if (headers.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    builder.header(entry.getKey(), entry.getValue().asText());
                }
            }

            // 设置方法和请求体
            HttpRequest.BodyPublisher bodyPublisher = bodyStr != null
                ? HttpRequest.BodyPublishers.ofString(bodyStr)
                : HttpRequest.BodyPublishers.noBody();

            switch (method) {
                case "POST"   -> builder.POST(bodyStr != null ? HttpRequest.BodyPublishers.ofString(bodyStr) : HttpRequest.BodyPublishers.noBody());
                case "PUT"    -> builder.PUT(bodyPublisher);
                case "DELETE" -> builder.DELETE();
                case "PATCH"  -> builder.method("PATCH", bodyPublisher);
                default       -> builder.GET();
            }

            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            if (responseBody != null && responseBody.length() > MAX_RESPONSE_CHARS) {
                responseBody = responseBody.substring(0, MAX_RESPONSE_CHARS) + "\n... (已截断)";
            }

            String result = "HTTP " + response.statusCode() + " " + method + " " + url + "\n" +
                            "响应:\n" + responseBody;

            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return ok
                ? ToolResult.ok(toolCallId, "http_request", result)
                : ToolResult.error(toolCallId, "http_request", result);

        } catch (Exception e) {
            return ToolResult.error(toolCallId, "http_request", e.getMessage());
        }
    }
}