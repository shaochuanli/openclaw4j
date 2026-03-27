package ai.openclaw.util;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存中的日志环形缓冲区。
 * <p>
 * 用于UI的"日志"标签页显示日志内容，保持最近2000条日志记录。
 * </p>
 */
public class LogBuffer {

    private static final LogBuffer INSTANCE = new LogBuffer(2000);

    private final int maxSize;
    private final Deque<String> buffer = new ConcurrentLinkedDeque<>();

    /**
     * 私有构造函数，创建指定大小的缓冲区。
     *
     * @param maxSize 最大日志条数
     */
    private LogBuffer(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * 获取单例实例。
     *
     * @return LogBuffer单例
     */
    public static LogBuffer getInstance() { return INSTANCE; }

    /**
     * 追加一条日志到缓冲区。
     * <p>
     * 当缓冲区满时，自动移除最早的日志条目。
     * </p>
     *
     * @param line 日志行内容
     */
    public void append(String line) {
        buffer.addLast(line);
        while (buffer.size() > maxSize) {
            buffer.pollFirst();
        }
    }

    /**
     * 获取最近N条日志。
     *
     * @param n 要获取的日志条数
     * @return 最近的N条日志列表
     */
    public List<String> tail(int n) {
        List<String> all = new ArrayList<>(buffer);
        int from = Math.max(0, all.size() - n);
        return all.subList(from, all.size());
    }

    /**
     * 获取所有日志。
     *
     * @return 所有日志列表
     */
    public List<String> all() {
        return new ArrayList<>(buffer);
    }

    /**
     * 清空缓冲区。
     */
    public void clear() {
        buffer.clear();
    }
}
