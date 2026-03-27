package ai.openclaw.tools.builtin;

import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工具：datetime
 * 获取当前日期/时间信息。
 */
public class DatetimeTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 获取工具定义。
     *
     * @return 日期时间工具的定义，包含参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode tz = MAPPER.createObjectNode();
        tz.put("type", "string");
        tz.put("description", "时区ID，如 'Asia/Shanghai'、'UTC'、'America/New_York'（默认：系统时区）");
        props.set("timezone", tz);

        params.set("properties", props);
        // 无必填字段

        return new ToolDefinition(
            "datetime",
            "获取当前日期和时间，包括时区、星期几、Unix时间戳等。",
            params
        );
    }

    /**
     * 执行日期时间查询。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，可包含timezone指定时区
     * @return 包含当前日期、时间、时区、星期、Unix时间戳等信息的执行结果
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        try {
            String tzId = args.path("timezone").asText(null);
            ZoneId zone = tzId != null && !tzId.isBlank() ? ZoneId.of(tzId) : ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone);

            String result = String.format(
                "当前日期时间:\n" +
                "  日期:       %s\n" +
                "  时间:       %s\n" +
                "  时区:       %s (%s)\n" +
                "  星期:       %s\n" +
                "  Unix时间戳: %d\n" +
                "  ISO 8601:   %s",
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                zone.getId(),
                now.format(DateTimeFormatter.ofPattern("zzz")),
                now.getDayOfWeek().name(),
                now.toEpochSecond(),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            );

            return ToolResult.ok(toolCallId, "datetime", result);
        } catch (Exception e) {
            return ToolResult.error(toolCallId, "datetime", e.getMessage());
        }
    }
}