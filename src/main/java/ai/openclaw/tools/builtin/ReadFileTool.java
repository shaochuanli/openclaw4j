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

/**
 * 工具：read_file
 * 读取本地文件的内容。
 */
public class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 最大读取字符数 */
    private static final int MAX_CHARS = 20000;

    /**
     * 获取工具定义。
     *
     * @return 文件读取工具的定义，包含path、offset、limit参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode path = MAPPER.createObjectNode();
        path.put("type", "string");
        path.put("description", "要读取的文件的绝对或相对路径");
        props.set("path", path);

        ObjectNode offset = MAPPER.createObjectNode();
        offset.put("type", "integer");
        offset.put("description", "开始读取的行号（从1开始，可选）");
        props.set("offset", offset);

        ObjectNode limit = MAPPER.createObjectNode();
        limit.put("type", "integer");
        limit.put("description", "最大读取行数（可选）");
        props.set("limit", limit);

        params.set("properties", props);
        params.putArray("required").add("path");

        return new ToolDefinition(
            "read_file",
            "从本地文件系统读取文件内容。支持可选的行偏移和行数限制。",
            params
        );
    }

    /**
     * 执行文件读取操作。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，包含path、可选的offset和limit
     * @return 包含文件内容的执行结果，带行号
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        String pathStr = args.path("path").asText("").trim();
        if (pathStr.isEmpty()) {
            return ToolResult.error(toolCallId, "read_file", "path is required");
        }

        try {
            Path path = Paths.get(pathStr);
            if (!Files.exists(path)) {
                return ToolResult.error(toolCallId, "read_file", "文件不存在: " + pathStr);
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error(toolCallId, "read_file", "路径是目录，请使用list_dir");
            }

            java.util.List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

            int offset = args.path("offset").asInt(1) - 1;
            int limit = args.path("limit").asInt(0);

            if (offset < 0) offset = 0;
            if (offset >= lines.size()) {
                return ToolResult.ok(toolCallId, "read_file", "(空：偏移超出文件末尾)");
            }

            java.util.List<String> selected = lines.subList(offset, lines.size());
            if (limit > 0 && limit < selected.size()) {
                selected = selected.subList(0, limit);
            }

            StringBuilder sb = new StringBuilder();
            int lineNum = offset + 1;
            for (String line : selected) {
                sb.append(lineNum++).append(":").append(line).append("\n");
                if (sb.length() > MAX_CHARS) {
                    sb.append("... (已截断)");
                    break;
                }
            }

            return ToolResult.ok(toolCallId, "read_file",
                "文件: " + pathStr + " (共 " + lines.size() + " 行)\n" + sb);

        } catch (Exception e) {
            return ToolResult.error(toolCallId, "read_file", e.getMessage());
        }
    }
}