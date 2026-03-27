package ai.openclaw.tools;

import ai.openclaw.tools.builtin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有可用工具/技能的中央注册表。
 * 代理通过名称引用技能（如"shell_exec"、"read_file"、"http_request"）。
 *
 * 内置技能组：
 *   - "shell"     → shell_exec
 *   - "files"     → read_file, write_file, list_dir
 *   - "http"      → http_request
 *   - "datetime"  → datetime
 *   - "cron"      → cron（动态注册）
 *   - "all"       → 所有上述工具
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 按工具名称索引的单个工具 */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** 技能组：组名称 → 工具名称列表（可动态扩展） */
    private final Map<String, List<String>> skillGroups = new HashMap<>();

    /** 内置技能组定义 */
    private static final Map<String, List<String>> BUILTIN_GROUPS = Map.of(
        "shell",    List.of("shell_exec"),
        "files",    List.of("read_file", "write_file", "list_dir"),
        "http",     List.of("http_request"),
        "datetime", List.of("datetime"),
        "all",      List.of("shell_exec", "read_file", "write_file", "list_dir", "http_request", "datetime")
    );

    /** 构造函数，注册所有内置工具 */
    public SkillRegistry() {
        // 初始化内置技能组
        skillGroups.putAll(BUILTIN_GROUPS);
        registerBuiltins();
    }

    /** 注册所有内置工具 */
    private void registerBuiltins() {
        register(new ShellExecTool());
        register(new ReadFileTool());
        register(new WriteFileTool());
        register(new ListDirTool());
        register(new HttpRequestTool());
        register(new DatetimeTool());
        log.info("Registered {} built-in tools", tools.size());
    }

    /**
     * 注册一个工具。
     *
     * @param tool 要注册的工具
     */
    public void register(Tool tool) {
        tools.put(tool.getDefinition().name, tool);
    }

    /**
     * 将技能引用列表（工具名称或组名称）解析为工具定义。
     *
     * @param skillRefs 技能引用列表，如"shell"、"files"、"read_file"等
     * @return 去重后的工具定义列表
     */
    public List<ToolDefinition> resolveSkills(List<String> skillRefs) {
        if (skillRefs == null || skillRefs.isEmpty()) return Collections.emptyList();

        Set<String> toolNames = new LinkedHashSet<>();
        for (String ref : skillRefs) {
            if (skillGroups.containsKey(ref)) {
                toolNames.addAll(skillGroups.get(ref));
            } else if (tools.containsKey(ref)) {
                toolNames.add(ref);
            } else {
                log.warn("Unknown skill or tool: '{}', ignoring", ref);
            }
        }

        List<ToolDefinition> defs = new ArrayList<>();
        for (String name : toolNames) {
            Tool t = tools.get(name);
            if (t != null) defs.add(t.getDefinition());
        }
        return defs;
    }

    /**
     * 按名称执行工具。
     *
     * @param name       工具名称
     * @param toolCallId 工具调用ID
     * @param args       参数JSON节点
     * @return 执行结果
     */
    public ToolResult executeTool(String name, String toolCallId, com.fasterxml.jackson.databind.JsonNode args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error(toolCallId, name, "Tool not found: " + name);
        }
        try {
            log.info("[Tool] Executing: {} args={}", name, args);
            ToolResult result = tool.execute(toolCallId, args);
            log.info("[Tool] Result: {} success={} content={}", name, result.success,
                result.content.length() > 200 ? result.content.substring(0, 200) + "..." : result.content);
            return result;
        } catch (Exception e) {
            log.error("[Tool] Execution failed: {}", name, e);
            return ToolResult.error(toolCallId, name, e.getMessage());
        }
    }

    /** 获取所有工具 */
    public Map<String, Tool> getAllTools() { return Collections.unmodifiableMap(tools); }

    /** 获取技能组映射 */
    public Map<String, List<String>> getSkillGroups() { return Collections.unmodifiableMap(skillGroups); }

    /**
     * 注册动态工具（运行时注入的工具）。
     * 同时更新 "all" 技能组以包含新工具。
     *
     * @param tool 要注册的工具
     */
    public void registerDynamicTool(Tool tool) {
        String toolName = tool.getDefinition().name;
        tools.put(toolName, tool);
        log.info("Registered dynamic tool: {}", toolName);

        // 更新 "all" 技能组
        List<String> allTools = new ArrayList<>(skillGroups.getOrDefault("all", List.of()));
        if (!allTools.contains(toolName)) {
            allTools.add(toolName);
            skillGroups.put("all", allTools);
        }
    }

    /**
     * 注册技能组。
     *
     * @param groupName 技能组名称
     * @param toolNames 工具名称列表
     */
    public void registerSkillGroup(String groupName, List<String> toolNames) {
        skillGroups.put(groupName, new ArrayList<>(toolNames));
        log.info("Registered skill group: {} -> {}", groupName, toolNames);
    }
}