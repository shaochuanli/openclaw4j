# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 构建与运行命令

```bash
# 构建（跳过测试以加快速度）
mvn package -DskipTests

# 构建并运行测试
mvn package

# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=类名

# 运行应用
java -jar target/openclaw4j-1.0.0.jar

# 指定端口运行
java -jar target/openclaw4j-1.0.0.jar --port 8080

# 开启详细日志
java -jar target/openclaw4j-1.0.0.jar --verbose
```

## 架构概览

OpenClaw4j 是一个基于 **Jetty**（HTTP/WebSocket）的单 JAR AI 助手网关，无 Spring 依赖。

```
ai.openclaw
├── OpenClaw4j.java          # 主入口，命令行解析
├── agents/
│   ├── AgentManager.java    # 核心 ReAct 循环：用户 → LLM → 工具 → LLM → 响应
│   ├── SessionManager.java  # 按发送者的会话历史（JSONL 持久化）
│   ├── providers/           # LLM API 客户端（OpenAI、Anthropic、Ollama）
│   └── UsageStats.java      # Token 计数统计
├── gateway/
│   ├── GatewayServer.java   # Jetty 服务器配置，绑定所有端点
│   ├── GatewayWebSocket.java # WebSocket RPC 处理器（方法分发）
│   ├── GatewayProtocol.java # 请求/响应/事件帧类型定义
│   └── ApiServlet.java      # REST /api/chat 端点
├── config/
│   ├── ConfigManager.java   # JSON 配置加载/保存/热重载
│   └── OpenClaw4jConfig.java # POJO 配置模型（嵌套类）
├── skills/
│   ├── SkillManager.java    # 从多源加载技能
│   ├── SkillLoader.java     # SKILL.md 文件扫描器
│   └── SkillScanner.java    # 安装前安全扫描
├── tools/
│   ├── SkillRegistry.java   # 内置工具注册表 + 技能分组
│   ├── Tool.java            # 工具接口
│   └── builtin/             # shell_exec、read_file、write_file、http_request 等
├── channels/
│   ├── Channel.java         # 抽象通道基类
│   └── TelegramChannel.java # Telegram 机器人实现
├── cron/
│   └── CronManager.java     # 基于 Quartz 的定时任务
└── util/
    └── LogBuffer.java       # 用于 UI 的内存日志缓冲
```

### 核心数据流

1. **聊天流程**：`GatewayWebSocket.handleChatSend()` → `AgentManager.runAsync()` → `ModelProvider.streamComplete()` → SSE 事件返回客户端
2. **工具执行**：LLM 返回 `tool_calls` → `AgentManager.executeToolCalls()` → `SkillRegistry.executeTool()` → 结果追加到历史
3. **配置热重载**：`ConfigManager.reload()` 通过 `Runnable` 回调通知注册的监听器

### WebSocket 协议

自定义帧协议（非 JSON-RPC）：
- 请求：`{"id":"1", "method":"chat.send", "params":{...}}`
- 响应：`{"type":"res", "id":"1", "ok":true, "payload":{...}}`
- 事件：`{"type":"event", "event":"chat", "payload":{...}}`

认证流程：服务器发送 `connect.challenge` → 客户端响应 `auth` 方法。

### 工具技能分组

Agent 在配置中通过组名引用工具：
- `"shell"` → `shell_exec`
- `"files"` → `read_file`、`write_file`、`list_dir`
- `"http"` → `http_request`
- `"datetime"` → `datetime`
- `"all"` → 所有内置工具

### 配置说明

配置文件：`~/.openclaw4j/openclaw4j.json`
- 环境变量替换：`"${OPENAI_API_KEY}"` 在运行时解析
- 模型引用格式：`"providerId/modelId"`（如 `"openai/gpt-4o"`）

## 代码风格

- 语言：Java 17+
- 文档注释使用中文
- DTO 使用 Lombok `@Data`/`@Builder`
- JSON 使用 Jackson（SKILL.md frontmatter 使用 YAML）
- 日志使用 SLF4J + Logback

## ReAct Agent 循环

核心执行模式：
```
用户输入 → LLM 推理 → 工具调用 → 工具结果 → LLM 推理 → 最终响应
```

最多循环 10 轮（`MAX_TOOL_ROUNDS`），防止无限循环。