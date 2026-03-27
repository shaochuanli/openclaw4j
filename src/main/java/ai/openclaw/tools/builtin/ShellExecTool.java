package ai.openclaw.tools.builtin;

import ai.openclaw.tools.Tool;
import ai.openclaw.tools.ToolDefinition;
import ai.openclaw.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 工具：shell_exec
 * 执行Shell命令并返回其输出。
 */
public class ShellExecTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 超时时间（秒）*/
    private static final int TIMEOUT_SECONDS = 30;
    /** 最大输出字符数 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /**
     * 获取工具定义。
     *
     * @return Shell执行工具的定义，包含command和cwd参数模式
     */
    @Override
    public ToolDefinition getDefinition() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode cmd = MAPPER.createObjectNode();
        cmd.put("type", "string");
        cmd.put("description", "要执行的Shell命令");
        props.set("command", cmd);

        ObjectNode cwd = MAPPER.createObjectNode();
        cwd.put("type", "string");
        cwd.put("description", "命令的工作目录（可选）");
        props.set("cwd", cwd);

        params.set("properties", props);
        params.putArray("required").add("command");

        return new ToolDefinition(
            "shell_exec",
            "在本地机器上执行Shell命令。返回标准输出、标准错误和退出码。" +
            "用于运行脚本、检查系统状态、通过CLI进行文件操作等。",
            params
        );
    }

    /**
     * 执行Shell命令。
     *
     * @param toolCallId 工具调用ID
     * @param args       参数，包含command和可选的cwd
     * @return 包含命令输出和退出码的执行结果
     */
    @Override
    public ToolResult execute(String toolCallId, JsonNode args) {
        String command = args.path("command").asText("").trim();
        String cwd = args.path("cwd").asText(null);

        if (command.isEmpty()) {
            return ToolResult.error(toolCallId, "shell_exec", "command is required");
        }

        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        output.append("\n... (输出已截断)");
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error(toolCallId, "shell_exec",
                    "命令在 " + TIMEOUT_SECONDS + " 秒后超时。当前输出:\n" + output);
            }

            int exitCode = process.exitValue();
            String result = "退出码: " + exitCode + "\n" + output;
            return exitCode == 0
                ? ToolResult.ok(toolCallId, "shell_exec", result)
                : ToolResult.error(toolCallId, "shell_exec", result);

        } catch (Exception e) {
            return ToolResult.error(toolCallId, "shell_exec", e.getMessage());
        }
    }
}