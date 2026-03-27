package ai.openclaw.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * openclaw4j.json中与技能相关的配置部分。
 * 映射到完整配置中的config.skills。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillsConfig {

    /** SkillHub商店URL（默认：https://skillhub.tencent.com） */
    public String storeUrl = "https://skillhub.tencent.com";

    /** 额外的技能目录扫描路径（除内置位置外） */
    public List<String> extraDirs = new ArrayList<>();

    /** 是否在SKILL.md更改时热重载技能 */
    public boolean watch = true;

    /** 每个源目录最大加载技能数 */
    public int maxSkillsPerSource = 200;

    /** 系统提示词中包含的最大技能数 */
    public int maxSkillsInPrompt = 50;

    /** 系统提示词中技能部分的最大字符数 */
    public int maxSkillsPromptChars = 20000;

    /** 每个SKILL.md文件允许的最大字节数 */
    public int maxSkillFileBytes = 262144; // 256KB

    /** 每个技能的配置覆盖：skillName -> SkillEntryConfig */
    public Map<String, SkillEntryConfig> entries = new LinkedHashMap<>();

    // ─── 嵌套类型 ─────────────────────────────────────────────────────────

    /**
     * 单个技能的配置。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SkillEntryConfig {
        /** 显式启用或禁用此技能 */
        public Boolean enabled;
        /** 需要认证的技能的API密钥 */
        public String apiKey;
        /** 此技能激活时要注入的额外环境变量 */
        public Map<String, String> env = new LinkedHashMap<>();
        /** 自定义配置值 */
        public Map<String, Object> config = new LinkedHashMap<>();
    }
}
