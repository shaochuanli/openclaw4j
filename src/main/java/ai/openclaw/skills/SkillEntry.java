package ai.openclaw.skills;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 内存中已加载技能条目的表示。
 * 包含解析后的元数据 + 原始SKILL.md内容 + 文件位置。
 */
public class SkillEntry {

    /** 此技能的来源 */
    public enum Source {
        BUNDLED,   // 随openclaw4j发布
        MANAGED,   // 安装在~/.openclaw4j/skills/
        WORKSPACE, // 在<workspaceDir>/skills/
        EXTRA      // 用户配置的额外目录
    }

    /** 从SKILL.md前置元数据解析的元数据 */
    public final SkillMeta meta;

    /** SKILL.md文件的完整路径 */
    public final Path skillFilePath;

    /** 此技能的基础目录（SKILL.md的父目录） */
    public final Path baseDir;

    /** 技能来源优先级 */
    public final Source source;

    /** 原始markdown正文内容（前置元数据之后），延迟加载 */
    private String body;
    private boolean bodyLoaded = false;

    /** 启用状态（来自配置覆盖） */
    public boolean enabled = true;

    /** 安装状态 */
    public InstallState installState = InstallState.UNKNOWN;

    /** 此条目加载时的时间戳 */
    public final long loadedAt = Instant.now().toEpochMilli();

    /** 商店技能ID（如果此技能是从SkillHub安装的） */
    public String storeSkillId;

    /** 商店评分（0-5）— 安装后从商店元数据填充 */
    public double storeRating;

    /** 商店下载次数 */
    public long storeDownloads;

    /** 商店是否有更新可用 */
    public boolean updateAvailable;

    /** 商店中可用的此技能版本（如果不同） */
    public String storeLatestVersion;

    /** 安装状态枚举 */
    public enum InstallState {
        UNKNOWN,
        READY,       // 所有要求已满足
        MISSING_DEPS,// 缺少二进制文件或环境变量
        NOT_ELIGIBLE // 操作系统不匹配，或显式禁用
    }

    /**
     * 构造函数。
     *
     * @param meta          技能元数据
     * @param skillFilePath SKILL.md文件路径
     * @param baseDir       技能基础目录
     * @param source        技能来源
     */
    public SkillEntry(SkillMeta meta, Path skillFilePath, Path baseDir, Source source) {
        this.meta = meta;
        this.skillFilePath = skillFilePath;
        this.baseDir = baseDir;
        this.source = source;
    }

    /**
     * 获取此技能的唯一键。
     * 如果设置了meta.skillKey则使用，否则使用meta.name。
     *
     * @return 技能唯一键
     */
    public String getKey() {
        return (meta.skillKey != null && !meta.skillKey.isBlank()) ? meta.skillKey : meta.name;
    }

    /**
     * 获取SKILL.md正文内容（YAML前置元数据之后的部分）。
     *
     * @return 正文内容
     */
    public String getBody() { return body; }

    /**
     * 设置SKILL.md正文内容。
     *
     * @param body 正文内容
     */
    public void setBody(String body) { this.body = body; this.bodyLoaded = true; }

    /**
     * 检查正文是否已加载。
     *
     * @return 正文是否已加载
     */
    public boolean isBodyLoaded() { return bodyLoaded; }

    /**
     * 获取SKILL.md的绝对路径字符串，home目录压缩为~。
     *
     * @return 显示用路径
     */
    public String getDisplayPath() {
        String absPath = skillFilePath.toAbsolutePath().toString();
        String home = System.getProperty("user.home");
        if (home != null && absPath.startsWith(home)) {
            return "~" + absPath.substring(home.length());
        }
        return absPath;
    }

    @Override
    public String toString() {
        return "SkillEntry{name=" + meta.name + ", source=" + source + ", path=" + getDisplayPath() + "}";
    }
}