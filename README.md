# OpenClaw4j 🦞

> **OpenClaw 的 Java 版本** — 自托管的个人 AI 助手网关

OpenClaw4j 是 [OpenClaw](https://openclaw.ai) 概念的轻量级 Java 实现。
运行你自己的 AI 助手网关，连接多个 LLM 提供商，通过精美的 Web UI 交互 — 全部自托管。

---

## 功能特性

| 功能 | 说明 |
|---|---|
| 🤖 **多模型支持** | OpenAI、Anthropic Claude、Ollama（本地）及任何 OpenAI 兼容 API |
| 💬 **Web 聊天界面** | 内置精美的控制面板，支持实时流式对话 |
| 📡 **多通道支持** | WebChat（内置）+ Telegram 机器人 + 飞书（Feishu/Lark） |
| 🔌 **WebSocket 长连接** | 飞书支持 WebSocket 模式，适合内网环境 |
| 🗂️ **会话管理** | 按发送者隔离会话，支持历史记录持久化 |
| ⏰ **定时任务** | 使用标准 cron 表达式调度 AI 任务 |
| 🔗 **Webhook** | HTTP webhook 触发 Agent 任务，支持外部系统集成 |
| ⚙️ **配置编辑器** | 在 Web UI 中直接编辑配置 |
| 📋 **实时日志** | 控制面板中查看实时日志 |
| 🔒 **令牌认证** | 使用共享令牌保护网关安全 |

---

## 快速开始

### 环境要求
- Java 17+（推荐 Java 21+）
- Maven 3.8+

### 构建

```bash
git clone <仓库地址>
cd openclaw4j
mvn package -DskipTests
```

### 运行

```bash
# Windows
start.bat

# Linux / macOS
./start.sh

# 或直接运行
java -jar target/openclaw4j-1.0.0.jar
```

在浏览器中打开 **http://localhost:18789/ui**。

---

## 配置说明

配置文件在首次运行时自动创建于 `~/.openclaw4j/openclaw4j.json`。

### 最简配置示例

```json
{
  "gateway": {
    "port": 18789,
    "auth": {
      "mode": "token",
      "token": "your-secret-token"
    }
  },
  "agents": {
    "agents": [
      {
        "id": "default",
        "name": "Assistant",
        "model": "openai/gpt-4o",
        "systemPrompt": "You are a helpful assistant."
      }
    ]
  },
  "models": {
    "providers": {
      "openai": {
        "api": "openai-completions",
        "baseUrl": "https://api.openai.com/v1",
        "apiKey": "${OPENAI_API_KEY}",
        "models": [
          { "id": "gpt-4o", "name": "GPT-4o" }
        ]
      }
    }
  }
}
```

### 使用 Ollama（本地模型）

```json
"models": {
  "providers": {
    "ollama": {
      "api": "ollama",
      "baseUrl": "http://localhost:11434",
      "models": [
        { "id": "llama3.2", "name": "Llama 3.2" }
      ]
    }
  }
}
```

然后将 agent 的 model 设置为 `"ollama/llama3.2"`。

### 启用 Telegram

```json
"channels": {
  "telegram": {
    "enabled": true,
    "token": "${TELEGRAM_BOT_TOKEN}",
    "dmPolicy": "open"
  }
}
```

### 启用飞书（Feishu/Lark）

飞书支持两种连接模式：

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `websocket` | 客户端主动连接飞书，保持长连接 | 内网环境、无公网 IP |
| `webhook` | 飞书主动向服务器推送事件 | 服务器有公网 IP |

**WebSocket 模式（推荐内网使用）：**

```json
"channels": {
  "feishu": {
    "enabled": true,
    "appId": "cli_xxxxxxxxxxxx",
    "appSecret": "${FEISHU_APP_SECRET}",
    "domain": "feishu",
    "connectionMode": "websocket",
    "dmPolicy": "open",
    "groupPolicy": "allowlist",
    "groupAllowFrom": ["oc_xxxxxxxxxxxx"]
  }
}
```

**Webhook 模式（需要公网 IP）：**

```json
"channels": {
  "feishu": {
    "enabled": true,
    "appId": "cli_xxxxxxxxxxxx",
    "appSecret": "${FEISHU_APP_SECRET}",
    "domain": "feishu",
    "connectionMode": "webhook",
    "webhookPath": "/feishu/events",
    "dmPolicy": "open"
  }
}
```

**飞书配置字段说明：**

| 字段 | 说明 |
|------|------|
| `appId` | 飞书应用的 App ID |
| `appSecret` | 飞书应用的 App Secret |
| `domain` | `"feishu"`（国内版）或 `"lark"`（国际版） |
| `connectionMode` | `"websocket"` 或 `"webhook"` |
| `webhookPath` | Webhook 路径，默认 `/feishu/events` |
| `dmPolicy` | 私聊策略：`"open"` / `"pairing"` / `"closed"` |
| `groupPolicy` | 群聊策略：`"open"` / `"allowlist"` / `"closed"` |
| `allowFrom` | 私聊白名单（用户 open_id） |
| `groupAllowFrom` | 群聊白名单（群聊 ID） |

**飞书应用配置步骤：**

1. 访问 [飞书开放平台](https://open.feishu.cn/) 创建企业自建应用
2. 获取 App ID 和 App Secret
3. 在「权限管理」中开通以下权限：
   - `im:message` - 获取与发送消息
   - `im:message:receive_as_bot` - 接收群聊消息
4. 在「事件订阅」中订阅 `im.message.receive_v1` 事件
   - WebSocket 模式：无需配置推送地址
   - Webhook 模式：配置推送地址为 `http://your-server:18789/feishu/events`

### 添加定时任务

```json
"cron": [
  {
    "id": "daily-report",
    "name": "每日简报",
    "schedule": "0 9 * * *",
    "agentId": "default",
    "prompt": "给我一个简短的早晨摘要，包含保持高效的建议。",
    "enabled": true
  }
]
```

---

## API 接口

### 健康检查
```
GET /healthz
→ {"status":"ok","version":"1.0.0"}
```

### REST 聊天 API
```
POST /api/chat
Authorization: Bearer <token>
Content-Type: application/json

{"agentId":"default","sessionKey":"api:myapp","text":"你好！"}
```

### WebSocket RPC

连接 `ws://localhost:18789/ws` 进行实时流式交互。

主要方法：
- `auth` — 认证
- `chat.send` — 发送消息（通过事件流式返回）
- `chat.history` — 获取会话历史
- `sessions.list` — 列出所有会话
- `sessions.reset` — 重置会话
- `cron.list/add/remove/run` — 管理定时任务
- `config.get/patch` — 读取/更新配置
- `models.list` — 列出可用模型
- `channels.status` — 获取通道状态
- `logs.tail` — 获取最近日志

### Webhook 触发
```
POST /webhook/{webhookId}
X-Webhook-Secret: <secret>

{"text": "自定义提示词"}
```

---

## 系统架构

```
┌─────────────────────────────────────────┐
│           OpenClaw4j Gateway            │
│         http://localhost:18789          │
│                                         │
│  /ui/*       → Web 控制面板 (HTML)      │
│  /ws         → WebSocket RPC 网关       │
│  /api/*      → REST API                 │
│  /webhook/*  → Webhook 触发器            │
│  /feishu/*   → 飞书事件 Webhook          │
│  /healthz    → 健康检查                  │
└──────────────┬──────────────────────────┘
               │
       ┌───────┴────────┐
       │  Agent Manager │
       │  (LLM 引擎)    │
       └───┬────────┬───┘
           │        │
    ┌──────┴─┐  ┌───┴──────┐
    │OpenAI  │  │ Ollama   │  ... (Anthropic 等)
    │Provider│  │ Provider │
    └────────┘  └──────────┘

┌─────────────────────────────────────────┐
│            消息通道                      │
│                                         │
│  WebChat ──── 内置 WebSocket UI         │
│  Telegram ─── Bot API (长轮询)          │
│  Feishu ───── WebSocket / Webhook       │
└─────────────────────────────────────────┘
```

---

## 命令行选项

```
java -jar openclaw4j-1.0.0.jar [选项]

  --port, -p <端口>     网关端口（默认：18789）
  --config, -c <路径>   配置文件路径
  --verbose, -v         详细日志输出
  --version             显示版本号
  --help, -h            显示帮助信息
```

---

## 许可证

MIT © OpenClaw4j 贡献者