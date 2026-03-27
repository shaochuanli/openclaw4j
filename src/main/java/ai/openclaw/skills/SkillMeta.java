package ai.openclaw.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 从SKILL.md前置元数据解析的元数据。
 * 映射openclaw的OpenClawSkillMetadata + frontmatter字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillMeta {

    // ─── 核心前置元数据字段 ──────────────────────────────────────────────

    /** 唯一技能标识符（必需） */
    public String name;

    /** 供LLM决定何时使用的简短描述（必需） */
    public String description;

    /** 用于显示的主页URL */
    public String homepage;

    /** 此技能是否可通过斜杠命令由用户调用（默认：true） */
    public Boolean userInvocable = true;

    /** 是否从模型提示词中排除（默认：false） */
    public Boolean disableModelInvocation = false;

    /** 版本字符串（如"1.0.0"） */
    public String version;

    /** 作者或发布者名称 */
    public String author;

    /** SkillHub商店技能ID（用于从商店安装的技能） */
    public String registryId;

    /** 安装此技能的SkillHub商店URL */
    public String storeUrl;

    /** SkillHub下载次数（从商店元数据填充） */
    public long downloads;

    /** SkillHub评分（0-5，从商店元数据填充） */
    public double rating;

    /** SkillHub评分数量 */
    public int ratingCount;

    /** 是否官方/商店认证 */
    public Boolean official;

    /** 商店中可用的最新版本（更新检查时填充） */
    public String latestVersion;

    /** 商店上是否有更新版本 */
    public Boolean updateAvailable;

    /** 分类标签 */
    public List<String> tags;

    // ─── OpenClaw特定元数据 ───────────────────────────────────────────

    /** 显示用的表情符号 */
    public String emoji;

    /** 始终启用，跳过所有检查门禁 */
    public Boolean always = false;

    /** 配置中使用的覆盖键（默认：name） */
    public String skillKey;

    /** 保存主API密钥的环境变量名 */
    public String primaryEnv;

    /** 支持的操作系统平台："win32"、"linux"、"darwin" */
    public List<String> os;

    /** 前置条件检查 */
    public Requires requires;

    /** 依赖安装规范 */
    public List<InstallSpec> install;

    /**
     * 技能前置元数据中声明的捆绑脚本文件。
     * 每个条目是技能目录内的相对路径（如"scripts/run.sh"）。
     * SkillMarkdownParser从{@code scripts} YAML字段填充此列表。
     */
    public List<String> scripts;

    /**
     * 技能提示词注入行为的规则类型。
     * 值："always" | "manual" | "requested"
     * - "always"    : 无论用户调用如何，始终注入技能提示词块
     * - "manual"    : 仅在通过API/配置显式加载时注入
     * - "requested" : 当模型或用户明确请求该技能时注入
     */
    public String ruleType;

    /**
     * 规则作用域："project"（工作区级别，覆盖用户）或"user"（用户级别默认）。
     * 映射到技能的安装作用域和配置覆盖优先级。
     */
    public String ruleScope;

    // ─── 嵌套类型 ─────────────────────────────────────────────────────────

    /** 前置条件要求 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Requires {
        /** PATH中必须存在的所有二进制文件 */
        public List<String> bins;
        /** 至少存在一个的二进制文件 */
        public List<String> anyBins;
        /** 必须存在的环境变量 */
        public List<String> env;
        /** 配置路径必须为真 */
        public List<String> config;
    }

    /** 安装规范 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InstallSpec {
        /** 技能内唯一的规范ID */
        public String id;
        /** 安装类型 */
        public String kind; // "winget" | "scoop" | "choco" | "npm" | "pip" | "download" | "go" | "shell"
        /** 人类可读的标签 */
        public String label;
        /** 安装后应存在的可执行文件 */
        public List<String> bins;
        /** 平台过滤器 */
        public List<String> os;
        /** 包/公式名称 */
        public String pkg;
        /** download类型的URL */
        public String url;
        /** 归档类型："zip" | "tar.gz" */
        public String archive;
        /** 是否解压归档 */
        public Boolean extract;
        /** 目标安装目录 */
        public String targetDir;
    }
}