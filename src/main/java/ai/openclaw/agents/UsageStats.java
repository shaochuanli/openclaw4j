package ai.openclaw.agents;

/**
 * 单次Agent运行的Token使用统计。
 */
public class UsageStats {
    /** 输入token数量 */
    public int inputTokens;
    /** 输出token数量 */
    public int outputTokens;
    /** 总token数量 */
    public int totalTokens;
    /** 估算成本 */
    public double cost;

    /** 默认构造函数 */
    public UsageStats() {}

    /**
     * 构造函数。
     *
     * @param inputTokens  输入token数量
     * @param outputTokens 输出token数量
     */
    public UsageStats(int inputTokens, int outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
    }

    /**
     * 累加另一个UsageStats的统计值。
     *
     * @param other 要累加的统计对象
     */
    public void add(UsageStats other) {
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens += other.totalTokens;
        this.cost += other.cost;
    }
}