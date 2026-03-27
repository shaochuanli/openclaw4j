package ai.openclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 描述可暴露给LLM的可调用工具（函数）。
 * 对应OpenAI的function-calling/tools模式。
 */
public class ToolDefinition {

    /** 工具名称 */
    public String name;
    /** 工具描述 */
    public String description;
    /** 描述参数的JSON Schema对象 */
    public JsonNode parameters;

    /**
     * 构造函数。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param parameters  参数JSON Schema
     */
    public ToolDefinition(String name, String description, JsonNode parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}