package ai.openclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 所有可执行本地工具的接口。
 */
public interface Tool {

    /** 返回工具的定义（模式）给LLM使用 */
    ToolDefinition getDefinition();

    /**
     * 使用解析后的参数执行工具。
     *
     * @param toolCallId 模型生成的调用ID
     * @param args       解析后的JSON参数
     * @return 工具执行结果
     */
    ToolResult execute(String toolCallId, JsonNode args);
}