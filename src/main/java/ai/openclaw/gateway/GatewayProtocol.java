package ai.openclaw.gateway;

import ai.openclaw.config.ConfigManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenClaw4j 网关的 WebSocket 协议帧定义。
 * 对应 openclaw 前端的 GatewayEventFrame / GatewayResponseFrame 协议格式。
 *
 * 协议帧类型说明：
 * - RequestFrame：客户端 → 服务端，发起 RPC 调用请求
 * - ResponseFrame：服务端 → 客户端，返回 RPC 调用结果
 * - EventFrame：服务端 → 客户端，主动推送事件（如 Agent 流式输出）
 * - ConnectChallenge：服务端 → 客户端，发起认证挑战
 * - AuthRequest：客户端 → 服务端，提交认证凭据
 */
public class GatewayProtocol {

    // ─── 请求帧（客户端 → 服务端）────────────────────────────────────

    /**
     * 客户端发起的 RPC 请求帧。
     * 反序列化时忽略未知字段，以保持向后兼容性。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequestFrame {
        public String id;       // 请求唯一标识，用于将响应与请求对应
        public String method;   // RPC 方法名称（如 "chat", "ping", "listAgents"）
        public Object params;   // 方法参数，具体类型由 method 决定
    }

    // ─── 响应帧（服务端 → 客户端）────────────────────────────────────

    /**
     * 服务端返回的 RPC 响应帧。
     * 序列化时忽略 null 字段，减少传输数据量。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseFrame {
        public String type = "res";  // 帧类型固定为 "res"
        public String id;            // 对应请求的 ID
        public boolean ok;           // 是否执行成功
        public Object payload;       // 成功时的返回数据
        public ErrorInfo error;      // 失败时的错误信息

        /**
         * 构建成功响应帧的工厂方法。
         *
         * @param id      对应请求的 ID
         * @param payload 响应数据
         * @return 成功响应帧实例
         */
        public static ResponseFrame ok(String id, Object payload) {
            ResponseFrame f = new ResponseFrame();
            f.id = id;
            f.ok = true;       // 标记为成功
            f.payload = payload;
            return f;
        }

        /**
         * 构建错误响应帧的工厂方法。
         *
         * @param id      对应请求的 ID
         * @param code    错误代码（如 "NOT_FOUND", "UNAUTHORIZED"）
         * @param message 错误描述信息
         * @return 错误响应帧实例
         */
        public static ResponseFrame error(String id, String code, String message) {
            ResponseFrame f = new ResponseFrame();
            f.id = id;
            f.ok = false;      // 标记为失败
            f.error = new ErrorInfo(code, message);
            return f;
        }
    }

    /**
     * 错误信息结构，包含错误代码、描述和可选的详细信息。
     * 序列化时忽略 null 字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorInfo {
        public String code;     // 错误代码，便于程序化处理
        public String message;  // 人类可读的错误描述
        public Object details;  // 可选的额外错误详情

        /**
         * 构造错误信息对象。
         *
         * @param code    错误代码
         * @param message 错误描述
         */
        public ErrorInfo(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    // ─── 事件推送帧（服务端 → 客户端，主动推送）────────────────────────────────────

    /**
     * 服务端主动推送的事件帧。
     * 用于实时推送 Agent 执行状态、流式输出、系统通知等。
     * 序列化时忽略 null 字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventFrame {
        public String type = "event"; // 帧类型固定为 "event"
        public String event;          // 事件名称（如 "agent.chunk", "agent.done"）
        public Object payload;        // 事件携带的数据
        public Long seq;              // 可选的序列号，用于事件排序

        /**
         * 构建事件帧的工厂方法。
         *
         * @param event   事件名称
         * @param payload 事件数据
         * @return 事件帧实例
         */
        public static EventFrame of(String event, Object payload) {
            EventFrame f = new EventFrame();
            f.event = event;
            f.payload = payload;
            return f;
        }
    }

    // ─── 认证帧 ────────────────────────────────────────────────────────

    /**
     * 连接认证挑战帧（服务端 → 客户端）。
     * WebSocket 建立后，服务端首先发送此帧要求客户端进行身份验证。
     * 序列化时忽略 null 字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConnectChallenge {
        public String challenge; // 认证挑战字符串（随机 nonce）
        public String mode;      // 认证模式（如 "token", "password", "none"）

        /**
         * 构造认证挑战帧。
         *
         * @param challenge 挑战字符串
         * @param mode      认证模式
         */
        public ConnectChallenge(String challenge, String mode) {
            this.challenge = challenge;
            this.mode = mode;
        }
    }

    /**
     * 客户端提交的认证请求帧（客户端 → 服务端）。
     * 反序列化时忽略未知字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthRequest {
        public String token;      // Bearer Token 认证令牌
        public String password;   // 密码认证凭据
        public String clientId;   // 客户端唯一标识
        public String clientName; // 客户端名称（用于展示）
    }

    // ─── 工具类型帧 ────────────────────────────────────────────────────────

    /**
     * Pong 响应帧，用于回复客户端的 ping 心跳请求。
     * 包含服务状态、当前时间戳和版本信息。
     */
    public static class Pong {
        public String status = "ok";                              // 服务状态，固定为 "ok"
        public long timestamp = System.currentTimeMillis();       // 当前服务器时间戳（毫秒）
        public String version = ai.openclaw.OpenClaw4j.VERSION;   // 服务版本号
    }
}
