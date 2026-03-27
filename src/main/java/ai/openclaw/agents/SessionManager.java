package ai.openclaw.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.openclaw.config.ConfigManager;

/**
 * 会话管理器，管理对话会话（历史记录的持久化和检索）。
 * 每个会话通过 sessionKey 标识（例如 "webchat:default", "telegram:+123456"）。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 配置管理器 */
    private final ConfigManager configManager;
    /** JSON序列化工具 */
    private final ObjectMapper mapper;
    /** 内存中的会话存储：sessionKey → 消息列表 */
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    /** 会话持久化目录 */
    private final Path sessionsDir;

    /**
     * 构造函数，初始化会话管理器。
     *
     * @param configManager 配置管理器
     * @param mapper        JSON序列化工具
     */
    public SessionManager(ConfigManager configManager, ObjectMapper mapper) {
        this.configManager = configManager;
        this.mapper = mapper;
        this.sessionsDir = Paths.get(ai.openclaw.OpenClaw4j.DEFAULT_CONFIG_DIR, "sessions");
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            log.warn("无法创建会话目录", e);
        }
    }

    /**
     * 获取或创建会话的消息历史。
     *
     * @param sessionKey 会话标识符
     * @return 该会话的消息列表
     */
    public List<ChatMessage> getOrCreate(String sessionKey) {
        return sessions.computeIfAbsent(sessionKey, k -> {
            List<ChatMessage> loaded = loadFromDisk(k);
            return loaded != null ? loaded : new ArrayList<>();
        });
    }

    /**
     * 向会话添加一条消息。
     *
     * @param sessionKey 会话标识符
     * @param message    要添加的消息
     */
    public void addMessage(String sessionKey, ChatMessage message) {
        List<ChatMessage> history = getOrCreate(sessionKey);
        history.add(message);

        // 历史消息过长时进行裁剪 — 必须原子性地移除工具调用组
        int maxMessages = configManager.getConfig().session.maxHistoryMessages;
        while (history.size() > maxMessages) {
            trimOneUnit(history);
        }

        if (configManager.getConfig().session.persistHistory) {
            saveToDisk(sessionKey, history);
        }
    }

    /**
     * 从历史记录中移除最早的"逻辑单元"以腾出空间。
     *
     * <p>一个逻辑单元是以下之一：
     * <ul>
     *   <li>系统消息（尽可能保留，最后移除）</li>
     *   <li>普通用户或助手消息</li>
     *   <li>包含tool_calls的助手消息<strong>以及</strong>紧随其后的所有工具结果消息 —
     *       它们必须一起移除，以避免向API发送孤立的{@code role:tool}消息（这会导致HTTP 400错误）</li>
     * </ul>
     *
     * <p>该方法总是从前部移除，跳过任何开头的系统消息。
     */
    private void trimOneUnit(List<ChatMessage> history) {
        if (history.isEmpty()) return;

        // 查找第一个非系统消息的索引，从这里开始移除
        int start = 0;
        if (!history.isEmpty() && history.get(0).role == ChatMessage.Role.system) {
            start = 1;
        }
        if (start >= history.size()) {
            // 只剩下系统消息 — 移除它
            history.remove(0);
            return;
        }

        ChatMessage first = history.get(start);

        // 如果这是一条包含tool_calls的助手消息，还必须移除所有紧随其后的工具结果消息
        if (first.role == ChatMessage.Role.assistant && first.rawToolCalls != null) {
            // 移除助手+tool_calls消息
            history.remove(start);
            // 移除所有紧随其后的工具消息
            while (start < history.size() && history.get(start).role == ChatMessage.Role.tool) {
                history.remove(start);
            }
        } else {
            // 普通消息（用户、普通助手或孤立工具）— 只移除这一条
            history.remove(start);
        }
    }

    /**
     * 重置（清空）会话的历史记录。
     *
     * @param sessionKey 会话标识符
     */
    public void resetSession(String sessionKey) {
        sessions.put(sessionKey, new ArrayList<>());
        saveToDisk(sessionKey, new ArrayList<>());
        log.info("会话已重置: {}", sessionKey);
    }

    /**
     * 完全删除一个会话。
     *
     * @param sessionKey 会话标识符
     */
    public void deleteSession(String sessionKey) {
        sessions.remove(sessionKey);
        try {
            Path file = getSessionFile(sessionKey);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("无法删除会话文件: {}", sessionKey);
        }
        log.info("会话已删除: {}", sessionKey);
    }

    /**
     * 列出所有会话及其元数据。
     *
     * @return 会话元数据列表，按最后消息时间降序排列
     */
    public List<Map<String, Object>> listSessions() {
        // 合并内存中和磁盘上的会话
        Set<String> allKeys = new HashSet<>(sessions.keySet());

        // 扫描磁盘
        try {
            if (Files.exists(sessionsDir)) {
                Files.list(sessionsDir).forEach(p -> {
                    String fileName = p.getFileName().toString();
                    if (fileName.endsWith(".json")) {
                        String key = fileNameToSessionKey(fileName.substring(0, fileName.length() - 5));
                        allKeys.add(key);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("无法列出会话目录", e);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : allKeys) {
            List<ChatMessage> history = getOrCreate(key);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionKey", key);
            entry.put("messageCount", history.size());

            // 获取最后一条消息的时间戳
            if (!history.isEmpty()) {
                ChatMessage last = history.get(history.size() - 1);
                entry.put("lastMessageAt", last.timestamp);
            }

            // 从key中提取渠道信息
            String[] parts = key.split(":", 2);
            entry.put("channel", parts[0]);
            entry.put("peer", parts.length > 1 ? parts[1] : "");

            result.add(entry);
        }

        result.sort((a, b) -> {
            Long ta = (Long) a.get("lastMessageAt");
            Long tb = (Long) b.get("lastMessageAt");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return Long.compare(tb, ta); // 最新的排在前面
        });

        return result;
    }

    /**
     * 获取会话预览（最后N条消息）。
     *
     * @param sessionKey 会话标识符
     * @return 包含会话信息和最近消息的Map
     */
    public Map<String, Object> getSessionPreview(String sessionKey) {
        List<ChatMessage> history = getOrCreate(sessionKey);
        List<ChatMessage> preview = history.size() > 10
            ? history.subList(history.size() - 10, history.size())
            : history;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionKey", sessionKey);
        result.put("messageCount", history.size());
        result.put("messages", preview);
        return result;
    }

    /**
     * 获取完整聊天历史（最后N条消息）。
     *
     * @param sessionKey 会话标识符
     * @param limit      最大消息数量限制
     * @return 包含会话信息和消息列表的Map
     */
    public Map<String, Object> getChatHistory(String sessionKey, int limit) {
        List<ChatMessage> history = getOrCreate(sessionKey);
        List<ChatMessage> messages = history.size() > limit
            ? history.subList(history.size() - limit, history.size())
            : new ArrayList<>(history);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionKey", sessionKey);
        result.put("total", history.size());
        result.put("messages", messages);
        return result;
    }

    // ─── 磁盘持久化 ────────────────────────────────────────────────────────────

    /**
     * 将会话保存到磁盘。
     *
     * @param sessionKey 会话标识符
     * @param messages   消息列表
     */
    private void saveToDisk(String sessionKey, List<ChatMessage> messages) {
        try {
            Path file = getSessionFile(sessionKey);
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), messages);
        } catch (IOException e) {
            log.warn("保存会话 {} 失败: {}", sessionKey, e.getMessage());
        }
    }

    /**
     * 从磁盘加载会话。
     *
     * @param sessionKey 会话标识符
     * @return 消息列表，如果不存在则返回null
     */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> loadFromDisk(String sessionKey) {
        try {
            Path file = getSessionFile(sessionKey);
            if (!Files.exists(file)) return null;
            List<ChatMessage> loaded = mapper.readValue(file.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
            // 清理：移除任何孤立的工具消息（没有前置的assistant+tool_calls）
            return sanitizeHistory(loaded);
        } catch (IOException e) {
            log.warn("加载会话 {} 失败: {}", sessionKey, e.getMessage());
            return null;
        }
    }

    /**
     * 移除任何没有紧邻前置assistant消息（包含tool_calls）的{@code role:tool}消息。
     * 这些孤立的消息会导致OpenAI API返回HTTP 400错误。
     *
     * @param history 原始历史记录
     * @return 清理后的历史记录
     */
    private List<ChatMessage> sanitizeHistory(List<ChatMessage> history) {
        if (history == null) return null;
        List<ChatMessage> clean = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (msg.role == ChatMessage.Role.tool) {
                // 只有当clean列表中的前一条消息是assistant+tool_calls时才保留
                if (!clean.isEmpty()) {
                    ChatMessage prev = clean.get(clean.size() - 1);
                    if (prev.role == ChatMessage.Role.assistant && prev.rawToolCalls != null) {
                        clean.add(msg);
                        continue;
                    }
                    // 也允许链式：如果前一条是工具消息且链头正确，也可以
                    if (prev.role == ChatMessage.Role.tool) {
                        clean.add(msg);
                        continue;
                    }
                }
                log.warn("丢弃会话历史中孤立的工具消息（没有前置的tool_calls）");
            } else {
                clean.add(msg);
            }
        }
        return clean;
    }

    /**
     * 获取会话文件路径。
     *
     * @param sessionKey 会话标识符
     * @return 会话文件路径
     */
    private Path getSessionFile(String sessionKey) {
        return sessionsDir.resolve(sessionKeyToFileName(sessionKey) + ".json");
    }

    /**
     * 将会话标识符转换为文件名。
     *
     * @param sessionKey 会话标识符
     * @return 安全的文件名
     */
    private String sessionKeyToFileName(String sessionKey) {
        return sessionKey.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /**
     * 将文件名转换回会话标识符（尽力恢复）。
     *
     * @param fileName 文件名
     * @return 会话标识符
     */
    private String fileNameToSessionKey(String fileName) {
        // 尽力恢复：还原已知模式的冒号
        return fileName.replaceFirst("^(webchat|telegram|discord|webhook|cron|api)_", "$1:");
    }
}