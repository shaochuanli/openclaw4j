package ai.openclaw.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 在代理运行期间检测工具调用循环。
 *
 * <p>参考项目中循环检测逻辑的Java实现。
 *
 * <p>应用两种检测策略：
 * <ol>
 *   <li><b>精确重复检测器</b> - 当相同的(toolName, normalizedArgs)对
 *       在同一会话中被调用超过WARN_THRESHOLD或BLOCK_THRESHOLD次时触发。</li>
 *   <li><b>高频检测器</b> - 当任何单个工具总共被调用超过HIGH_FREQ_WARN或HIGH_FREQ_BLOCK次时触发，
 *       无论参数如何。</li>
 * </ol>
 *
 * <p>结果：
 * <ul>
 *   <li>{@link DetectResult#ok()} — 正常继续</li>
 *   <li>{@link DetectResult#warn(String, int, String)} — 记录警告但继续执行</li>
 *   <li>{@link DetectResult#block(String, int, String)} — 拒绝工具调用并返回错误</li>
 * </ul>
 *
 * <p>线程安全：实例不在会话之间共享。
 * 每次会话运行创建一个ToolLoopDetector，会话结束时丢弃。
 */
public class ToolLoopDetector {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopDetector.class);

    // ─── 阈值 ────────────────────────────────────────────────────────────────

    /** 相同(工具,参数)对：相同调用达到此数量后发出警告 */
    private static final int WARN_THRESHOLD  = 3;
    /** 相同(工具,参数)对：相同调用达到此数量后阻止调用 */
    private static final int BLOCK_THRESHOLD = 6;

    /** 任何单个工具：总调用达到此数量后发出警告 */
    private static final int HIGH_FREQ_WARN  = 8;
    /** 任何单个工具：总调用达到此数量后阻止调用 */
    private static final int HIGH_FREQ_BLOCK = 15;

    /** 要跟踪的(工具,参数)指纹的最大数量，用于限制内存使用 */
    private static final int MAX_FINGERPRINTS = 512;

    // ─── 状态 ────────────────────────────────────────────────────────────────

    /** 指纹(toolName:argsHash) → 计数 */
    private final Map<String, Integer> exactCounts = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_FINGERPRINTS;
        }
    };

    /** toolName → 总调用计数 */
    private final Map<String, Integer> toolTotalCounts = new HashMap<>();

    // ─── 结果 ────────────────────────────────────────────────────────────────

    /** 检测结果级别 */
    public enum Level { OK, WARN, CRITICAL }

    /** 检测结果 */
    public static class DetectResult {
        /** 结果级别 */
        public final Level level;
        /** 消息 */
        public final String message;
        /** 计数 */
        public final int count;
        /** 检测器类型："exact-repeat" | "high-frequency" */
        public final String detector;

        private DetectResult(Level level, String message, int count, String detector) {
            this.level    = level;
            this.message  = message;
            this.count    = count;
            this.detector = detector;
        }

        /** 创建正常结果 */
        public static DetectResult ok() {
            return new DetectResult(Level.OK, null, 0, null);
        }

        /** 创建警告结果 */
        public static DetectResult warn(String message, int count, String detector) {
            return new DetectResult(Level.WARN, message, count, detector);
        }

        /** 创建阻止结果 */
        public static DetectResult block(String message, int count, String detector) {
            return new DetectResult(Level.CRITICAL, message, count, detector);
        }

        /** 是否应阻止 */
        public boolean isBlocking()  { return level == Level.CRITICAL; }
        /** 是否为警告 */
        public boolean isWarning()   { return level == Level.WARN; }
        /** 是否正常 */
        public boolean isOk()        { return level == Level.OK; }
    }

    // ─── 公共API ────────────────────────────────────────────────────────────────

    /**
     * 记录工具调用并检测是否看起来像循环。
     *
     * <p>必须在执行工具之前调用。如果结果为{@link Level#CRITICAL}，
     * 则应拒绝调用。如果为{@link Level#WARN}，记录警告但允许执行。
     *
     * @param toolName 规范化的工具名称（如{@code "shell_exec"}）
     * @param argsJson 原始JSON参数字符串（用于指纹识别）
     * @return 检测结果
     */
    public DetectResult detect(String toolName, String argsJson) {
        String normalized = normalize(toolName);
        String fp         = fingerprint(normalized, argsJson);

        // 精确重复检查
        int exactCount = exactCounts.merge(fp, 1, Integer::sum);

        if (exactCount >= BLOCK_THRESHOLD) {
            String msg = String.format(
                "Tool '%s' called %d times with identical arguments — aborting to prevent infinite loop.",
                normalized, exactCount);
            log.error("[ToolLoop] BLOCK: {}", msg);
            return DetectResult.block(msg, exactCount, "exact-repeat");
        }
        if (exactCount >= WARN_THRESHOLD) {
            String msg = String.format(
                "Tool '%s' has been called %d times with identical arguments. Possible loop?",
                normalized, exactCount);
            log.warn("[ToolLoop] WARN: {}", msg);
            return DetectResult.warn(msg, exactCount, "exact-repeat");
        }

        // 高频检查
        int totalCount = toolTotalCounts.merge(normalized, 1, Integer::sum);

        if (totalCount >= HIGH_FREQ_BLOCK) {
            String msg = String.format(
                "Tool '%s' has been called %d times in this session — blocking to prevent runaway loop.",
                normalized, totalCount);
            log.error("[ToolLoop] BLOCK (high-freq): {}", msg);
            return DetectResult.block(msg, totalCount, "high-frequency");
        }
        if (totalCount >= HIGH_FREQ_WARN) {
            String msg = String.format(
                "Tool '%s' has been called %d times in this session — possible loop?",
                normalized, totalCount);
            log.warn("[ToolLoop] WARN (high-freq): {}", msg);
            return DetectResult.warn(msg, totalCount, "high-frequency");
        }

        return DetectResult.ok();
    }

    /**
     * 重置所有计数器（例如，当同一会话开始新的代理循环时）。
     */
    public void reset() {
        exactCounts.clear();
        toolTotalCounts.clear();
    }

    /**
     * 返回此会话中指定工具的总调用计数。
     *
     * @param toolName 工具名称
     * @return 调用计数
     */
    public int getCount(String toolName) {
        return toolTotalCounts.getOrDefault(normalize(toolName), 0);
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────────

    /**
     * 规范化工具名称：小写，将非字母数字序列替换为'_'。
     *
     * @param toolName 工具名称
     * @return 规范化后的名称
     */
    public static String normalize(String toolName) {
        if (toolName == null) return "tool";
        return toolName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    /**
     * 计算(toolName, args)对的稳定指纹。
     * 对规范化的参数进行哈希以保持映射键紧凑。
     *
     * @param toolName 工具名称
     * @param argsJson 参数JSON字符串
     * @return 指纹字符串
     */
    private static String fingerprint(String toolName, String argsJson) {
        String args = argsJson != null ? argsJson.trim() : "{}";
        // 简单但稳定：FNV-1a 32位哈希
        return toolName + ":" + fnv32(args);
    }

    /** FNV-1a 32位哈希算法 */
    private static int fnv32(String s) {
        int hash = 0x811c9dc5;
        for (char c : s.toCharArray()) {
            hash ^= c;
            hash *= 0x01000193;
        }
        return hash;
    }
}