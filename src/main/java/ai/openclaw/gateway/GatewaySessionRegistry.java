package ai.openclaw.gateway;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃 WebSocket 连接（已认证客户端）的注册中心。
 * 维护一个线程安全的客户端连接映射表，支持：
 * - 客户端连接注册与注销
 * - 查询当前连接数
 * - 向所有连接的客户端广播事件
 *
 * 使用 ConcurrentHashMap 保证多线程并发访问的安全性。
 */
public class GatewaySessionRegistry {

    // 客户端 ID → WebSocket 实例的映射表，使用 ConcurrentHashMap 保证线程安全
    private final Map<String, GatewayWebSocket> clients = new ConcurrentHashMap<>();

    /**
     * 注册一个已认证的 WebSocket 客户端连接。
     *
     * @param clientId 客户端唯一标识（由客户端在认证时提供）
     * @param client   对应的 WebSocket 连接实例
     */
    public void register(String clientId, GatewayWebSocket client) {
        clients.put(clientId, client);
    }

    /**
     * 注销并移除一个已断开的客户端连接。
     *
     * @param clientId 要注销的客户端唯一标识
     */
    public void unregister(String clientId) {
        clients.remove(clientId);
    }

    /**
     * 返回所有当前活跃的 WebSocket 连接集合。
     *
     * @return 所有活跃连接的集合（线程安全视图）
     */
    public Collection<GatewayWebSocket> getAll() {
        return clients.values();
    }

    /**
     * 返回当前活跃连接的数量。
     *
     * @return 活跃连接数
     */
    public int size() {
        return clients.size();
    }

    /**
     * 向所有已连接的客户端广播事件。
     * 遍历所有活跃会话，调用各自的 sendEvent 方法推送事件。
     * 适用于 Agent 状态变更、系统通知等需要全局广播的场景。
     *
     * @param event   事件名称（如 "agent.chunk", "agent.done", "system.notice"）
     * @param payload 事件携带的数据对象，将被序列化为 JSON
     */
    public void broadcast(String event, Object payload) {
        clients.values().forEach(c -> c.sendEvent(event, payload));
    }
}
