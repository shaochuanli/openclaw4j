package ai.openclaw.tools;

/**
 * 表示LLM请求的单个工具调用。
 * 对应OpenAI的choices[0].message.tool_calls[n]。
 */
public class ToolCall {

    /** 模型生成的工具调用ID（用于关联结果） */
    public String id;

    /** 要调用的工具名称 */
    public String name;

    /** 原始JSON格式的参数字符串 */
    public String arguments;

    /**
     * 构造函数。
     *
     * @param id       工具调用ID
     * @param name     工具名称
     * @param arguments 参数JSON字符串
     */
    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }
}