package ai.openclaw.tools.builtin;

import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

/**
 * 工具：list_dir
 * 列出路径下的文件和目录。
 */
public class ListDirTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 获取工具定义。
     *
     * @return 目录列表工具的定义，包含path和recursive参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode path = MAPPER.createObjectNode();
        path.put("type", "string");
        path.put("description", "要列出的目录路径");
        props.set("path", path);

        ObjectNode recursive = MAPPER.createObjectNode();
        recursive.put("type", "boolean");
        recursive.put("description", "是否递归列出（默认：false）。大目录请谨慎使用。");
        props.set("recursive", recursive);

        params.set("properties", props);
        params.putArray("required").add("path");

        return new ToolDefinition(
            "list_dir",
            "列出给定路径下的文件和目录，显示名称、类型、大小和修改时间。",
            params
        );
    }

    /**
     * 执行目录列表查询。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，包含path和recursive选项
     * @return 包含目录内容的执行结果，显示名称、类型、大小和修改时间
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        String pathStr = args.path("path").asText("").trim();
        boolean recursive = args.path("recursive").asBoolean(false);

        if (pathStr.isEmpty()) {
            return ToolResult.error(toolCallId, "list_dir", "path is required");
        }

        try {
            Path dir = Paths.get(pathStr);
            if (!Files.exists(dir)) {
                return ToolResult.error(toolCallId, "list_dir", "路径不存在: " + pathStr);
            }
            if (!Files.isDirectory(dir)) {
                return ToolResult.error(toolCallId, "list_dir", "不是目录: " + pathStr);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(pathStr).append("\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            if (recursive) {
                listRecursive(dir.toFile(), sb, "", 0, 3, sdf);
            } else {
                File[] files = dir.toFile().listFiles();
                if (files == null || files.length == 0) {
                    sb.append("(空目录)");
                } else {
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    for (File f : files) {
                        appendEntry(sb, f, sdf, "");
                    }
                    sb.append("\n共: ").append(files.length).append(" 项");
                }
            }

            return ToolResult.ok(toolCallId, "list_dir", sb.toString());

        } catch (Exception e) {
            return ToolResult.error(toolCallId, "list_dir", e.getMessage());
        }
    }

    /**
     * 递归列出目录内容。
     *
     * @param dir      要列出的目录
     * @param sb       用于构建输出的StringBuilder
     * @param indent   当前缩进
     * @param depth    当前深度
     * @param maxDepth 最大递归深度
     * @param sdf      日期格式化器
     */
    private void listRecursive(File dir, StringBuilder sb, String indent, int depth, int maxDepth, SimpleDateFormat sdf) {
        if (depth > maxDepth) {
            sb.append(indent).append("... (已达到最大深度)\n");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            appendEntry(sb, f, sdf, indent);
            if (f.isDirectory() && depth < maxDepth) {
                listRecursive(f, sb, indent + "  ", depth + 1, maxDepth, sdf);
            }
        }
    }

    /**
     * 追加单个文件或目录条目到输出。
     *
     * @param sb     用于构建输出的StringBuilder
     * @param f      文件或目录
     * @param sdf    日期格式化器
     * @param indent 缩进字符串
     */
    private void appendEntry(StringBuilder sb, File f, SimpleDateFormat sdf, String indent) {
        String type = f.isDirectory() ? "目录" : "文件";
        String size = f.isDirectory() ? "        " : String.format("%8s", formatSize(f.length()));
        String time = sdf.format(new Date(f.lastModified()));
        sb.append(indent)
          .append("[").append(type).append("] ")
          .append(size).append("  ")
          .append(time).append("  ")
          .append(f.getName())
          .append("\n");
    }

    /**
     * 格式化文件大小为人类可读格式。
     *
     * @param bytes 字节数
     * @return 格式化后的大小字符串，如"1.5KB"、"2.3MB"
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }
}