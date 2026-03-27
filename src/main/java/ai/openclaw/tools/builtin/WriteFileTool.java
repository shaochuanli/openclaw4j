package ai.openclaw.tools.builtin;

import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 工具：write_file
 * 写入或追加内容到本地文件。
 */
public class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 获取工具定义。
     *
     * @return 文件写入工具的定义，包含path、content、mode参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode path = MAPPER.createObjectNode();
        path.put("type", "string");
        path.put("description", "要写入的文件的绝对或相对路径");
        props.set("path", path);

        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "string");
        content.put("description", "要写入文件的内容");
        props.set("content", content);

        ObjectNode mode = MAPPER.createObjectNode();
        mode.put("type", "string");
        mode.put("description", "写入模式：'overwrite'（覆盖，默认）或 'append'（追加）");
        mode.putArray("enum").add("overwrite").add("append");
        props.set("mode", mode);

        params.set("properties", props);
        params.putArray("required").add("path").add("content");

        return new ToolDefinition(
            "write_file",
            "写入或追加内容到本地文件系统的文件。如需要会创建父目录。",
            params
        );
    }

    /**
     * 执行文件写入操作。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，包含path、content和可选的mode
     * @return 包含写入结果的执行结果
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        String pathStr = args.path("path").asText("").trim();
        String content = args.path("content").asText("");
        String mode = args.path("mode").asText("overwrite");

        if (pathStr.isEmpty()) {
            return ToolResult.error(toolCallId, "write_file", "path is required");
        }

        try {
            Path path = Paths.get(pathStr);
            // 如需要则创建父目录
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            if ("append".equals(mode)) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            return ToolResult.ok(toolCallId, "write_file",
                "成功写入 " + content.length() + " 个字符到: " + pathStr);

        } catch (Exception e) {
            return ToolResult.error(toolCallId, "write_file", e.getMessage());
        }
    }
}