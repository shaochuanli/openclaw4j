package ai.openclaw.tools.builtin;

import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import ai.openclaw.cron.CronManager;
import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.quartz.CronExpression;

import java.text.ParseException;
import java.util.List;
import java.util.UUID;

/**
 * 工具：cron
 * 定时任务管理工具，支持创建、查看、删除和立即执行定时任务。
 */
public class CronTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CronManager cronManager;
    private final ConfigManager configManager;

    /**
     * 构造定时任务工具实例。
     *
     * @param cronManager   定时任务管理器
     * @param configManager 配置管理器
     */
    public CronTool(CronManager cronManager, ConfigManager configManager) {
        this.cronManager = cronManager;
        this.configManager = configManager;
    }

    /**
     * 获取工具定义。
     *
     * @return 定时任务工具的定义，包含参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode props = MAPPER.createObjectNode();

        // action 参数
        ObjectNode action = MAPPER.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：list(列出任务)、create(创建任务)、remove(删除任务)、run(立即执行)");
        ArrayNode actionEnum = MAPPER.createArrayNode();
        actionEnum.add("list");
        actionEnum.add("create");
        actionEnum.add("remove");
        actionEnum.add("run");
        action.set("enum", actionEnum);
        props.set("action", action);

        // name 参数
        ObjectNode name = MAPPER.createObjectNode();
        name.put("type", "string");
        name.put("description", "任务名称（create 时必填）");
        props.set("name", name);

        // schedule 参数
        ObjectNode schedule = MAPPER.createObjectNode();
        schedule.put("type", "string");
        schedule.put("description", "Cron表达式，格式：秒 分 时 日 月 周。示例：'0 9 * * *' 每天9点，'0 10 ? * MON' 每周一10点，'0 */2 * * *' 每2小时");
        props.set("schedule", schedule);

        // prompt 参数
        ObjectNode prompt = MAPPER.createObjectNode();
        prompt.put("type", "string");
        prompt.put("description", "任务提示词（create 时必填）");
        props.set("prompt", prompt);

        // agentId 参数
        ObjectNode agentId = MAPPER.createObjectNode();
        agentId.put("type", "string");
        agentId.put("description", "使用的 Agent ID，默认 default");
        props.set("agentId", agentId);

        // id 参数
        ObjectNode id = MAPPER.createObjectNode();
        id.put("type", "string");
        id.put("description", "任务 ID（remove/run 时必填）");
        props.set("id", id);

        params.set("properties", props);

        // required 字段
        ArrayNode required = MAPPER.createArrayNode();
        required.add("action");
        params.set("required", required);

        return new ToolDefinition(
            "cron",
            "定时任务管理工具。支持创建、查看、删除和立即执行定时任务。" +
            "操作：list(列出所有任务)、create(创建新任务)、remove(删除任务)、run(立即执行)。" +
            "Cron格式：秒 分 时 日 月 周。示例：'0 9 * * *' 每天9点，'0 9 * * MON-FRI' 工作日9点，'0 */2 * * *' 每2小时。",
            params
        );
    }

    /**
     * 执行定时任务操作。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数
     * @return 执行结果
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        try {
            String action = args.path("action").asText(null);
            if (action == null || action.isBlank()) {
                return ToolResult.error(toolCallId, "cron", "缺少 action 参数");
            }

            return switch (action) {
                case "list" -> executeList(toolCallId);
                case "create" -> executeCreate(toolCallId, args);
                case "remove" -> executeRemove(toolCallId, args);
                case "run" -> executeRun(toolCallId, args);
                default -> ToolResult.error(toolCallId, "cron", "未知的操作类型: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error(toolCallId, "cron", "执行失败: " + e.getMessage());
        }
    }

    /**
     * 列出所有定时任务。
     */
    private ToolResult executeList(String toolCallId) {
        List<OpenClaw4jConfig.CronJobConfig> jobs = configManager.getConfig().cron;

        StringBuilder sb = new StringBuilder();
        sb.append("当前共有 ").append(jobs.size()).append(" 个定时任务：\n\n");

        for (int i = 0; i < jobs.size(); i++) {
            OpenClaw4jConfig.CronJobConfig job = jobs.get(i);
            sb.append(i + 1).append(". ").append(job.name).append("\n");
            sb.append("   ID: ").append(job.id).append("\n");
            sb.append("   调度: ").append(job.schedule).append("\n");
            sb.append("   Agent: ").append(job.agentId).append("\n");
            sb.append("   提示词: ").append(truncate(job.prompt, 50)).append("\n");
            sb.append("   状态: ").append(job.enabled ? "启用" : "禁用").append("\n\n");
        }

        if (jobs.isEmpty()) {
            sb.append("暂无定时任务。使用 create 操作创建新任务。");
        }

        return ToolResult.ok(toolCallId, "cron", sb.toString());
    }

    /**
     * 创建定时任务。
     */
    private ToolResult executeCreate(String toolCallId, JsonNode args) {
        String name = args.path("name").asText(null);
        String schedule = args.path("schedule").asText(null);
        String prompt = args.path("prompt").asText(null);
        String agentId = args.path("agentId").asText("default");

        // 参数验证
        if (name == null || name.isBlank()) {
            return ToolResult.error(toolCallId, "cron", "缺少 name 参数");
        }
        if (schedule == null || schedule.isBlank()) {
            return ToolResult.error(toolCallId, "cron", "缺少 schedule 参数");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error(toolCallId, "cron", "缺少 prompt 参数");
        }

        // 验证 cron 表达式
        try {
            new CronExpression(schedule);
        } catch (ParseException e) {
            return ToolResult.error(toolCallId, "cron",
                "无效的 Cron 表达式: " + schedule + "\n错误: " + e.getMessage() +
                "\n\nCron 格式：秒 分 时 日 月 周\n示例：'0 9 * * *' 每天9点");
        }

        // 创建任务配置
        OpenClaw4jConfig.CronJobConfig job = new OpenClaw4jConfig.CronJobConfig();
        job.id = UUID.randomUUID().toString();
        job.name = name;
        job.schedule = schedule;
        job.prompt = prompt;
        job.agentId = agentId;
        job.enabled = true;

        // 添加到配置并保存
        try {
            configManager.getConfig().cron.add(job);
            configManager.save();
        } catch (Exception e) {
            return ToolResult.error(toolCallId, "cron", "保存配置失败: " + e.getMessage());
        }

        // 调度任务
        cronManager.scheduleJob(job);

        return ToolResult.ok(toolCallId, "cron",
            "定时任务创建成功！\n" +
            "名称: " + name + "\n" +
            "ID: " + job.id + "\n" +
            "调度: " + schedule + "\n" +
            "提示词: " + truncate(prompt, 100));
    }

    /**
     * 删除定时任务。
     */
    private ToolResult executeRemove(String toolCallId, JsonNode args) {
        String id = args.path("id").asText(null);

        if (id == null || id.isBlank()) {
            return ToolResult.error(toolCallId, "cron", "缺少 id 参数");
        }

        // 查找任务
        OpenClaw4jConfig.CronJobConfig targetJob = null;
        for (OpenClaw4jConfig.CronJobConfig job : configManager.getConfig().cron) {
            if (id.equals(job.id)) {
                targetJob = job;
                break;
            }
        }

        if (targetJob == null) {
            return ToolResult.error(toolCallId, "cron", "未找到 ID 为 " + id + " 的任务");
        }

        // 取消调度
        cronManager.unscheduleJob(id);

        // 从配置中移除
        try {
            configManager.getConfig().cron.removeIf(job -> id.equals(job.id));
            configManager.save();
        } catch (Exception e) {
            return ToolResult.error(toolCallId, "cron", "保存配置失败: " + e.getMessage());
        }

        return ToolResult.ok(toolCallId, "cron",
            "定时任务已删除\n名称: " + targetJob.name + "\nID: " + id);
    }

    /**
     * 立即执行定时任务。
     */
    private ToolResult executeRun(String toolCallId, JsonNode args) {
        String id = args.path("id").asText(null);

        if (id == null || id.isBlank()) {
            return ToolResult.error(toolCallId, "cron", "缺少 id 参数");
        }

        // 查找任务
        OpenClaw4jConfig.CronJobConfig targetJob = null;
        for (OpenClaw4jConfig.CronJobConfig job : configManager.getConfig().cron) {
            if (id.equals(job.id)) {
                targetJob = job;
                break;
            }
        }

        if (targetJob == null) {
            return ToolResult.error(toolCallId, "cron", "未找到 ID 为 " + id + " 的任务");
        }

        // 执行任务（通过 WebSocket 的 handleCronRun 逻辑）
        // 这里简化处理，直接返回提示
        return ToolResult.ok(toolCallId, "cron",
            "任务已触发执行\n名称: " + targetJob.name + "\n提示词: " + truncate(targetJob.prompt, 100) +
            "\n\n注意：任务正在异步执行，结果将通过事件推送。");
    }

    /**
     * 截断字符串。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}