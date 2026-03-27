package ai.openclaw.agents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.*;

/**
 * 对话会话中的聊天消息。
 * 扩展支持工具/函数调用角色。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    /**
     * 消息角色。
     * - system:    系统提示词
     * - user:      人类输入
     * - assistant: 模型响应（可能包含工具调用请求）
     * - tool:      工具执行结果（对应OpenAI的"tool"角色）
     */
    public enum Role { system, user, assistant, tool }

    /** 消息角色 */
    public Role role;
    /** 消息内容 */
    public String content;
    /** 消息唯一标识符 */
    public String id;
    /** 消息时间戳（毫秒）*/
    public Long timestamp;
    /** Token使用统计 */
    public UsageStats usage;

    /** 对于工具角色消息：此结果所响应的tool_call_id */
    public String toolCallId;

    /**
     * 对于包含tool_calls的助手消息：tool_calls的原始JSON数组字符串，用于重新嵌入API请求。
     * （存储为字符串以避免Jackson处理动态工具模式的复杂性）
     */
    public String rawToolCalls;

    /** 默认构造函数 */
    public ChatMessage() {}

    /**
     * 构造函数。
     *
     * @param role    消息角色
     * @param content 消息内容
     */
    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toEpochMilli();
    }

    /**
     * 创建工具结果消息。
     *
     * @param toolCallId 工具调用ID
     * @param toolName   工具名称（当前未使用）
     * @param content    工具执行结果内容
     * @return 工具结果消息
     */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        ChatMessage msg = new ChatMessage(Role.tool, content);
        msg.toolCallId = toolCallId;
        return msg;
    }

    /**
     * 转换为API请求格式的Map。
     *
     * @return 包含消息字段的Map
     */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role.name());
        if (content != null) m.put("content", content);
        if (toolCallId != null) m.put("tool_call_id", toolCallId);
        return m;
    }
}