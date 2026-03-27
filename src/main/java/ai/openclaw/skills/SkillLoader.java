package ai.openclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 扫描目录中的SKILL.md文件并将其加载为SkillEntry对象。
 *
 * <p>搜索顺序（低优先级优先，后加载的覆盖先加载的同名冲突）：
 * <ol>
 *   <li>额外目录（用户配置）</li>
 *   <li>捆绑技能（随openclaw4j JAR发布）</li>
 *   <li>托管技能（~/.openclaw4j/skills/）</li>
 *   <li>工作区技能（&lt;workspaceDir&gt;/skills/）</li>
 * </ol>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final int maxSkillsPerSource;
    private final long maxSkillFileBytes;

    /**
     * 构造函数。
     *
     * @param maxSkillsPerSource 每个来源最大技能数
     * @param maxSkillFileBytes  每个技能文件最大字节数
     */
    public SkillLoader(int maxSkillsPerSource, long maxSkillFileBytes) {
        this.maxSkillsPerSource = maxSkillsPerSource;
        this.maxSkillFileBytes = maxSkillFileBytes;
    }

    /**
     * 从指定目录加载所有技能。
     * 查找 &lt;dir&gt;/&lt;skillName&gt;/SKILL.md 模式。
     *
     * @param dir    要扫描的根目录
     * @param source 已加载条目的来源标记
     * @return 已加载的SkillEntry列表（尽力而为，错误会被记录）
     */
    public List<SkillEntry> loadFromDir(Path dir, SkillEntry.Source source) {
        if (dir == null || !Files.isDirectory(dir)) return Collections.emptyList();

        List<SkillEntry> results = new ArrayList<>();

        // 检查是否为 <dir>/skills/ 模式（自动检测嵌套）
        Path skillsSubDir = dir.resolve("skills");
        Path scanRoot = Files.isDirectory(skillsSubDir) ? skillsSubDir : dir;

        try {
            // 仅列出直接子目录
            List<Path> subdirs = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(scanRoot, Files::isDirectory)) {
                for (Path sub : stream) {
                    subdirs.add(sub);
                    if (subdirs.size() >= 300) break; // 安全限制
                }
            }

            for (Path subdir : subdirs) {
                if (results.size() >= maxSkillsPerSource) {
                    log.warn("Hit maxSkillsPerSource ({}) scanning {}", maxSkillsPerSource, scanRoot);
                    break;
                }
                Path skillMd = subdir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                SkillEntry entry = loadSingle(skillMd, subdir, source);
                if (entry != null) results.add(entry);
            }

            // 同时检查根目录中直接的SKILL.md（扁平结构）
            Path rootSkillMd = scanRoot.resolve("SKILL.md");
            if (Files.isRegularFile(rootSkillMd) && results.isEmpty()) {
                SkillEntry entry = loadSingle(rootSkillMd, scanRoot, source);
                if (entry != null) results.add(entry);
            }

        } catch (IOException e) {
            log.warn("Error scanning skills dir {}: {}", scanRoot, e.getMessage());
        }

        log.info("Loaded {} skills from {} (source={})", results.size(), scanRoot, source);
        return results;
    }

    /**
     * 加载单个SKILL.md文件。
     *
     * @param skillMdPath SKILL.md文件路径
     * @param baseDir     技能基础目录
     * @param source      技能来源
     * @return 加载的SkillEntry，失败返回null
     */
    public SkillEntry loadSingle(Path skillMdPath, Path baseDir, SkillEntry.Source source) {
        try {
            long fileSize = Files.size(skillMdPath);
            if (fileSize > maxSkillFileBytes) {
                log.warn("SKILL.md too large ({} bytes), skipping: {}", fileSize, skillMdPath);
                return null;
            }

            String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            SkillMarkdownParser.ParseResult parsed = SkillMarkdownParser.parse(content, skillMdPath.toString());
            if (parsed == null) return null;

            SkillEntry entry = new SkillEntry(parsed.meta, skillMdPath, baseDir, source);
            entry.setBody(parsed.body);
            return entry;

        } catch (IOException e) {
            log.warn("Failed to read SKILL.md at {}: {}", skillMdPath, e.getMessage());
            return null;
        }
    }

    /**
     * 获取托管技能目录：~/.openclaw4j/skills/
     *
     * @return 托管技能目录路径
     */
    public static Path getManagedSkillsDir() {
        return Paths.get(System.getProperty("user.home"), ".openclaw4j", "skills");
    }

    /**
     * 获取捆绑技能目录：JAR旁边或classpath资源中。
     *
     * @return 捆绑技能目录路径，不存在则返回null
     */
    public static Path getBundledSkillsDir() {
        // 检查环境变量覆盖
        String override = System.getenv("OPENCLAW4J_BUNDLED_SKILLS_DIR");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override.trim());
            if (Files.isDirectory(p)) return p;
        }
        // 检查JAR可执行文件旁边
        try {
            Path execDir = Paths.get(SkillLoader.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getParent();
            if (execDir != null) {
                Path sibling = execDir.resolve("skills");
                if (Files.isDirectory(sibling)) return sibling;
            }
        } catch (Exception ignored) {}

        return null;
    }
}
