package ai.openclaw.channels;

/**
 * 所有消息通道的抽象基类。
 * <p>
 * 定义了通道的基本生命周期方法，包括启动、停止和状态查询。
 * </p>
 */
public abstract class Channel {

    protected final String channelId;
    protected final String channelName;
    protected boolean running = false;

    /**
     * 构造通道实例。
     *
     * @param channelId   通道唯一标识符
     * @param channelName 通道显示名称
     */
    public Channel(String channelId, String channelName) {
        this.channelId = channelId;
        this.channelName = channelName;
    }

    /**
     * 启动通道。
     * <p>
     * 子类需实现具体的连接逻辑，如建立连接、注册webhooks等。
     * </p>
     *
     * @throws Exception 启动过程中发生异常
     */
    public abstract void start() throws Exception;

    /**
     * 停止通道。
     * <p>
     * 子类需实现具体的断开逻辑，释放相关资源。
     * </p>
     */
    public abstract void stop();

    /**
     * 获取通道当前状态。
     *
     * @return 状态字符串，可能的值包括："online"、"offline"、"error"、"disabled"
     */
    public abstract String getStatus();

    /**
     * 获取通道唯一标识符。
     *
     * @return 通道ID
     */
    public String getChannelId() { return channelId; }

    /**
     * 获取通道显示名称。
     *
     * @return 通道名称
     */
    public String getChannelName() { return channelName; }

    /**
     * 检查通道是否正在运行。
     *
     * @return 如果通道正在运行返回true，否则返回false
     */
    public boolean isRunning() { return running; }
}
