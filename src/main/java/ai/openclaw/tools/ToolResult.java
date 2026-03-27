package ai.openclaw.tools;

/**
 * 执行工具调用后的结果。
 */
public class ToolResult {

    /** 与ToolCall.id匹配 */
    public String toolCallId;

    /** 工具名称 */
    public String name;

    /** 结果内容（文本、JSON、错误消息等） */
    public String content;

    /** 执行是否成功 */
    public boolean success;

    /**
     * 构造函数。
     *
     * @param toolCallId 工具调用ID
     * @param name       工具名称
     * @param content    结果内容
     * @param success    是否成功
     */
    public ToolResult(String toolCallId, String name, String content, boolean success) {
        this.toolCallId = toolCallId;
        this.name = name;
        this.content = content;
        this.success = success;
    }

    /**
     * 创建成功的结果。
     *
     * @param toolCallId 工具调用ID
     * @param name       工具名称
     * @param content    结果内容
     * @return 成功的ToolResult
     */
    public static ToolResult ok(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, true);
    }

    /**
     * 创建错误的结果。
     *
     * @param toolCallId 工具调用ID
     * @param name       工具名称
     * @param message    错误消息
     * @return 失败的ToolResult
     */
    public static ToolResult error(String toolCallId, String name, String message) {
        return new ToolResult(toolCallId, name, "ERROR: " + message, false);
    }
}