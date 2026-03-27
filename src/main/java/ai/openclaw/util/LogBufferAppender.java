package ai.openclaw.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Logback日志追加器。
 * <p>
 * 将日志事件转发到内存中的LogBuffer，允许UI实时流式显示日志输出。
 * </p>
 */
public class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());

    /**
     * 追加日志事件到缓冲区。
     * <p>
     * 将日志事件格式化为字符串并添加到LogBuffer。
     * </p>
     *
     * @param event 日志事件
     */
    @Override
    protected void append(ILoggingEvent event) {
        String time = FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String level = event.getLevel().toString();
        String logger = abbreviateLogger(event.getLoggerName());
        String msg = event.getFormattedMessage();

        String line = String.format("%s [%s] %s - %s", time, level, logger, msg);
        LogBuffer.getInstance().append(line);
    }

    /**
     * 缩写Logger名称。
     * <p>
     * 将完整的包名缩写为首字母加点的形式，如"a.o.c.ConfigManager"。
     * </p>
     *
     * @param name 完整的Logger名称
     * @return 缩写后的Logger名称
     */
    private String abbreviateLogger(String name) {
        if (name == null) return "?";
        String[] parts = name.split("\\.");
        if (parts.length <= 2) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }
}
