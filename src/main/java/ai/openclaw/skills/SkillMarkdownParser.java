package ai.openclaw.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 将SKILL.md文件解析为(SkillMeta, body)对。
 *
 * <p>SKILL.md格式：
 * <pre>
 * ---
 * name: my-skill
 * description: What this skill does.
 * homepage: https://example.com
 * version: 1.0.0
 * emoji: 🔧
 * always: false
 * os: [win32, linux]
 * requires:
 *   bins: [git]
 *   env: [MY_API_KEY]
 * install:
 *   - id: winget
 *     kind: winget
 *     pkg: Git.Git
 *     bins: [git]
 * ---
 *
 * # Skill content
 * Instructions for the LLM...
 * </pre>
 */
public class SkillMarkdownParser {

    private static final Logger log = LoggerFactory.getLogger(SkillMarkdownParser.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 解析结果。
     */
    public static class ParseResult {
        /** 技能元数据 */
        public SkillMeta meta;
        /** 前置元数据后的markdown正文 */
        public String body;
    }

    /**
     * 将完整的SKILL.md内容字符串解析为元数据和正文。
     *
     * @param content  完整文件内容
     * @param filePath 用于错误报告的文件路径
     * @return ParseResult，解析失败返回null
     */
    public static ParseResult parse(String content, String filePath) {
        if (content == null || content.isBlank()) return null;

        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");

        // 提取---分隔符之间的YAML前置元数据
        if (!normalized.startsWith("---")) {
            // 无前置元数据 — 尝试将整个内容作为YAML解析？不，跳过。
            log.warn("SKILL.md has no frontmatter (---): {}", filePath);
            return null;
        }

        int endFm = normalized.indexOf("\n---", 3);
        if (endFm < 0) {
            log.warn("SKILL.md frontmatter not closed: {}", filePath);
            return null;
        }

        String yamlBlock = normalized.substring(4, endFm).trim(); // 跳过第一个"---\n"
        String body = normalized.substring(endFm + 4).stripLeading(); // 跳过关闭的"---"

        try {
            // 使用Jackson YAML将前置元数据解析为扁平map
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = YAML_MAPPER.readValue(yamlBlock, Map.class);
            if (raw == null) raw = new LinkedHashMap<>();

            SkillMeta meta = mapToMeta(raw, filePath);
            if (meta.name == null || meta.name.isBlank()) {
                log.warn("SKILL.md missing required 'name' field: {}", filePath);
                return null;
            }
            if (meta.description == null || meta.description.isBlank()) {
                log.warn("SKILL.md missing required 'description' field: {}", filePath);
                return null;
            }

            ParseResult result = new ParseResult();
            result.meta = meta;
            result.body = body;
            return result;

        } catch (Exception e) {
            log.warn("Failed to parse SKILL.md frontmatter at {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 将Map转换为SkillMeta对象。
     *
     * @param raw      原始数据Map
     * @param filePath 文件路径（用于错误报告）
     * @return SkillMeta对象
     */
    @SuppressWarnings("unchecked")
    private static SkillMeta mapToMeta(Map<String, Object> raw, String filePath) {
        SkillMeta m = new SkillMeta();

        m.name = str(raw, "name");
        m.description = str(raw, "description");
        m.homepage = str(raw, "homepage");
        m.version = str(raw, "version");
        m.author = str(raw, "author");
        m.registryId = str(raw, "registry-id");
        m.emoji = str(raw, "emoji");
        m.skillKey = str(raw, "skill-key");
        m.primaryEnv = str(raw, "primary-env");

        Object userInv = raw.get("user-invocable");
        if (userInv instanceof Boolean b) m.userInvocable = b;

        Object disableModel = raw.get("disable-model-invocation");
        if (disableModel instanceof Boolean b) m.disableModelInvocation = b;

        Object always = raw.get("always");
        if (always instanceof Boolean b) m.always = b;

        // tags
        Object tags = raw.get("tags");
        if (tags instanceof List<?> list) {
            m.tags = list.stream().map(Object::toString).toList();
        }

        // os
        Object os = raw.get("os");
        if (os instanceof List<?> list) {
            m.os = list.stream().map(Object::toString).toList();
        } else if (os instanceof String s) {
            m.os = List.of(s.split("[,\\s]+"));
        }

        // requires
        Object req = raw.get("requires");
        if (req instanceof Map<?, ?> reqMap) {
            SkillMeta.Requires r = new SkillMeta.Requires();
            r.bins = strList(reqMap, "bins");
            r.anyBins = strList(reqMap, "any-bins");
            r.env = strList(reqMap, "env");
            r.config = strList(reqMap, "config");
            if (r.bins != null || r.anyBins != null || r.env != null || r.config != null) {
                m.requires = r;
            }
        }

        // install specs
        Object installList = raw.get("install");
        if (installList instanceof List<?> specs) {
            m.install = new ArrayList<>();
            for (Object s : specs) {
                if (s instanceof Map<?, ?> specMap) {
                    SkillMeta.InstallSpec spec = new SkillMeta.InstallSpec();
                    spec.id = str(specMap, "id");
                    spec.kind = str(specMap, "kind");
                    spec.label = str(specMap, "label");
                    spec.pkg = str(specMap, "pkg");
                    spec.url = str(specMap, "url");
                    spec.archive = str(specMap, "archive");
                    spec.targetDir = str(specMap, "target-dir");
                    Object extract = specMap.get("extract");
                    if (extract instanceof Boolean b) spec.extract = b;
                    spec.bins = strList(specMap, "bins");
                    spec.os = strList(specMap, "os");
                    m.install.add(spec);
                }
            }
        }

        // scripts: 捆绑脚本文件路径列表，相对于技能目录
        // 如 scripts: [scripts/run.sh, scripts/setup.py]
        m.scripts = strList(raw, "scripts");

        // ruleType: "always" | "manual" | "requested"
        m.ruleType = str(raw, "ruleType");
        if (m.ruleType == null) m.ruleType = str(raw, "rule-type"); // 连字符别名

        // ruleScope: "project" | "user"
        m.ruleScope = str(raw, "ruleScope");
        if (m.ruleScope == null) m.ruleScope = str(raw, "rule-scope");

        // metadata.openclaw（OpenClaw扩展元数据块）
        Object metadata = raw.get("metadata");
        if (metadata instanceof Map<?, ?> metaMap) {
            Object ocRaw = metaMap.get("openclaw");
            if (ocRaw instanceof Map<?, ?> oc) {
                if (m.emoji == null) m.emoji = str(oc, "emoji");
                if (m.skillKey == null) m.skillKey = str(oc, "skillKey");
                if (m.primaryEnv == null) m.primaryEnv = str(oc, "primaryEnv");
                Object alwaysOc = oc.get("always");
                if (alwaysOc instanceof Boolean b) m.always = b;
                // os from openclaw block
                Object osOc = oc.get("os");
                if (osOc instanceof List<?> list && m.os == null) {
                    m.os = list.stream().map(Object::toString).toList();
                }
                // requires from openclaw block
                Object reqOc = oc.get("requires");
                if (reqOc instanceof Map<?, ?> reqMap && m.requires == null) {
                    SkillMeta.Requires r = new SkillMeta.Requires();
                    r.bins = strList(reqMap, "bins");
                    r.anyBins = strList(reqMap, "anyBins");
                    r.env = strList(reqMap, "env");
                    r.config = strList(reqMap, "config");
                    m.requires = r;
                }
                // install from openclaw block
                Object installOc = oc.get("install");
                if (installOc instanceof List<?> specs && m.install == null) {
                    m.install = new ArrayList<>();
                    for (Object s : specs) {
                        if (s instanceof Map<?, ?> specMap) {
                            SkillMeta.InstallSpec spec = new SkillMeta.InstallSpec();
                            spec.id = str(specMap, "id");
                            spec.kind = str(specMap, "kind");
                            spec.label = str(specMap, "label");
                            spec.pkg = str(specMap, "package");
                            if (spec.pkg == null) spec.pkg = str(specMap, "formula");
                            spec.url = str(specMap, "url");
                            spec.archive = str(specMap, "archive");
                            spec.bins = strList(specMap, "bins");
                            spec.os = strList(specMap, "os");
                            m.install.add(spec);
                        }
                    }
                }
                // scripts from openclaw block
                if (m.scripts == null) m.scripts = strList(oc, "scripts");
                // ruleType / ruleScope from openclaw block
                if (m.ruleType == null) {
                    m.ruleType = str(oc, "ruleType");
                    if (m.ruleType == null) m.ruleType = str(oc, "rule-type");
                }
                if (m.ruleScope == null) {
                    m.ruleScope = str(oc, "ruleScope");
                    if (m.ruleScope == null) m.ruleScope = str(oc, "rule-scope");
                }
            }
        }

        return m;
    }

    /**
     * 从Map中获取字符串值。
     *
     * @param map Map对象
     * @param key 键
     * @return 字符串值，不存在返回null
     */
    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return (v != null) ? v.toString().trim() : null;
    }

    /**
     * 从Map中获取字符串列表值。
     *
     * @param map Map对象
     * @param key 键
     * @return 字符串列表，不存在返回null
     */
    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("[,\\s]+"));
        }
        return null;
    }
}
