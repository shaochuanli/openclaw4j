package ai.openclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 处理技能的下载、安装、更新和卸载。
 * 支持从SkillHub商店或任意URL安装。
 *
 * <p>安装位置：~/.openclaw4j/skills/&lt;skillName&gt;/
 *
 * <p>安装模式：
 * <ol>
 *   <li>仅SKILL.md — 只写入SKILL.md文件</li>
 *   <li>ZIP归档 — 解压zip到技能目录</li>
 * </ol>
 */
public class SkillInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    /** 安全限制：SKILL.md最大允许大小（1 MB） */
    private static final long MAX_SKILL_MD_BYTES = 1024 * 1024;

    /** 安全限制：zip最大允许大小（50 MB） */
    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;

    /** 安全限制：每个zip最大解压文件数 */
    private static final int MAX_ZIP_ENTRIES = 500;

    private final SkillHubClient hubClient;
    private final SkillLoader loader;

    /**
     * 安装模式枚举。
     */
    public enum InstallMode {
        /** 仅SKILL.md文件 */
        SKILL_MD_ONLY,
        /** ZIP归档 */
        ZIP_ARCHIVE
    }

    // ─── 安装结果 ───────────────────────────────────────────────────────

    /**
     * 安装结果。
     */
    public static class InstallResult {
        /** 是否成功 */
        public final boolean success;
        /** 技能名称 */
        public final String skillName;
        /** 安装目录 */
        public final Path installDir;
        /** 错误信息 */
        public final String error;
        /** 安装后的技能条目 */
        public final SkillEntry entry;

        private InstallResult(boolean success, String skillName, Path installDir,
                              String error, SkillEntry entry) {
            this.success = success;
            this.skillName = skillName;
            this.installDir = installDir;
            this.error = error;
            this.entry = entry;
        }

        /**
         * 创建成功结果。
         *
         * @param skillName  技能名称
         * @param installDir 安装目录
         * @param entry      技能条目
         * @return 成功的InstallResult
         */
        public static InstallResult ok(String skillName, Path installDir, SkillEntry entry) {
            return new InstallResult(true, skillName, installDir, null, entry);
        }

        /**
         * 创建失败结果。
         *
         * @param skillName 技能名称
         * @param error     错误信息
         * @return 失败的InstallResult
         */
        public static InstallResult fail(String skillName, String error) {
            return new InstallResult(false, skillName, null, error, null);
        }

        @Override
        public String toString() {
            return success
                ? "InstallResult{ok, name=" + skillName + ", path=" + installDir + "}"
                : "InstallResult{FAIL, name=" + skillName + ", error=" + error + "}";
        }
    }

    // ─── 构造函数 ──────────────────────────────────────────────────────────

    /**
     * 构造函数。
     *
     * @param hubClient SkillHub客户端
     */
    public SkillInstaller(SkillHubClient hubClient) {
        this.hubClient = hubClient;
        this.loader = new SkillLoader(200, MAX_SKILL_MD_BYTES);
    }

    // ─── 从商店安装 ───────────────────────────────────────────────────

    /**
     * 从SkillHub商店通过商店技能ID安装技能。
     * 优先使用zip归档（如果有），否则退回到仅SKILL.md。
     *
     * @param storeSkill  商店技能信息
     * @param managedDir  托管技能目录（如 ~/.openclaw4j/skills/）
     * @param forceUpdate 是否强制覆盖已安装的
     * @return 安装结果
     */
    public InstallResult installFromStore(SkillHubClient.StoreSkill storeSkill,
                                          Path managedDir, boolean forceUpdate) {
        String skillName = sanitizeSkillName(storeSkill.name);
        Path skillDir = managedDir.resolve(skillName);

        // 检查是否已安装
        if (!forceUpdate && Files.isDirectory(skillDir)) {
            Path existingMd = skillDir.resolve("SKILL.md");
            if (Files.isRegularFile(existingMd)) {
                log.info("Skill '{}' already installed at {}", skillName, skillDir);
                SkillEntry existing = loader.loadSingle(existingMd, skillDir, SkillEntry.Source.MANAGED);
                if (existing != null) {
                    return InstallResult.ok(skillName, skillDir, existing);
                }
            }
        }

        try {
            Files.createDirectories(managedDir);

            // 优先尝试zip
            if (storeSkill.zipUrl != null && !storeSkill.zipUrl.isBlank()) {
                log.info("Downloading skill zip for '{}': {}", skillName, storeSkill.zipUrl);
                byte[] zipBytes = hubClient.downloadSkillZip(storeSkill.zipUrl);
                if (zipBytes != null && zipBytes.length > 0) {
                    return installFromZip(skillName, zipBytes, managedDir, forceUpdate);
                }
                log.warn("Zip download failed for '{}', falling back to SKILL.md", skillName);
            }

            // 回退：仅SKILL.md
            if (storeSkill.skillMdUrl != null && !storeSkill.skillMdUrl.isBlank()) {
                log.info("Downloading SKILL.md for '{}': {}", skillName, storeSkill.skillMdUrl);
                String content = hubClient.downloadSkillMd(storeSkill.skillMdUrl);
                if (content != null) {
                    return installSkillMd(skillName, content, managedDir, forceUpdate);
                }
            }

            return InstallResult.fail(skillName, "No download URL available for skill: " + storeSkill.id);

        } catch (Exception e) {
            log.error("Install failed for skill '{}': {}", skillName, e.getMessage(), e);
            return InstallResult.fail(skillName, e.getMessage());
        }
    }

    /**
     * 通过商店ID安装技能 — 查找商店并安装。
     *
     * @param storeSkillId 商店技能ID
     * @param managedDir   托管技能目录
     * @param forceUpdate  是否强制更新
     * @return 安装结果
     */
    public InstallResult installById(String storeSkillId, Path managedDir, boolean forceUpdate) {
        log.info("Looking up skill '{}' from store...", storeSkillId);
        var opt = hubClient.getSkill(storeSkillId);
        if (opt.isEmpty()) {
            return InstallResult.fail(storeSkillId, "Skill not found in store: " + storeSkillId);
        }
        return installFromStore(opt.get(), managedDir, forceUpdate);
    }

    // ─── 从SKILL.md内容安装 ───────────────────────────────────────

    /**
     * 从SKILL.md原始文本内容安装技能。
     *
     * <p>如果内容没有YAML前置元数据（没有开头的{@code ---}），
     * 会自动添加最小化的前置元数据块，使解析器始终能成功解析。
     * 这使得纯文本技能描述（如从SkillHub安装URL获取的）无需手动编辑即可安装。
     *
     * @param skillName      技能名称
     * @param skillMdContent SKILL.md内容
     * @param managedDir     托管技能目录
     * @param overwrite      是否覆盖已有
     * @return 安装结果
     */
    public InstallResult installSkillMd(String skillName, String skillMdContent,
                                         Path managedDir, boolean overwrite) {
        skillName = sanitizeSkillName(skillName);

        if (skillMdContent == null || skillMdContent.isBlank()) {
            return InstallResult.fail(skillName, "Empty SKILL.md content");
        }

        // ── 如果缺少前置元数据则自动注入 ───────────────────────────────
        String normalized = skillMdContent.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.stripLeading().startsWith("---")) {
            // 从skillName构建最小前置元数据
            // 使用第一个非空行作为描述（截断到200字符）
            String firstLine = normalized.lines()
                .filter(l -> !l.isBlank())
                .findFirst().orElse("Installed skill: " + skillName);
            if (firstLine.length() > 200) firstLine = firstLine.substring(0, 197) + "...";
            // 转义描述中的YAML特殊字符
            String safeDesc = firstLine.replace("\"", "'");
            String safeKey  = skillName.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
            String frontmatter = String.format(
                "---\nname: %s\ndescription: \"%s\"\nversion: \"1.0.0\"\n---\n\n",
                safeKey, safeDesc);
            skillMdContent = frontmatter + normalized;
            log.info("Auto-injected frontmatter for skill '{}' (content had no ---)", skillName);
        }

        if (skillMdContent.getBytes(StandardCharsets.UTF_8).length > MAX_SKILL_MD_BYTES) {
            return InstallResult.fail(skillName, "SKILL.md too large");
        }

        try {
            Path skillDir = managedDir.resolve(skillName);
            Path skillMdPath = skillDir.resolve("SKILL.md");

            if (!overwrite && Files.isRegularFile(skillMdPath)) {
                // 重新解析现有文件；如果是损坏版本（无前置元数据），则覆盖
                SkillEntry existing = loader.loadSingle(skillMdPath, skillDir, SkillEntry.Source.MANAGED);
                if (existing != null) {
                    return InstallResult.fail(skillName, "Already installed (use update to overwrite)");
                }
                log.info("Overwriting unreadable existing SKILL.md for '{}'", skillName);
            }

            Files.createDirectories(skillDir);
            Files.writeString(skillMdPath, skillMdContent, StandardCharsets.UTF_8);
            log.info("Installed SKILL.md for '{}' at {}", skillName, skillMdPath);

            SkillEntry entry = loader.loadSingle(skillMdPath, skillDir, SkillEntry.Source.MANAGED);
            if (entry == null) {
                return InstallResult.fail(skillName, "SKILL.md written but failed to parse — check frontmatter");
            }
            return InstallResult.ok(skillName, skillDir, entry);

        } catch (IOException e) {
            log.error("Failed to write SKILL.md for '{}': {}", skillName, e.getMessage());
            return InstallResult.fail(skillName, "IO error: " + e.getMessage());
        }
    }

    // ─── 从ZIP安装 ─────────────────────────────────────────────────────

    /**
     * 从zip归档字节安装技能。
     * zip必须包含SKILL.md在根目录或单个顶层目录中。
     *
     * @param skillName  技能名称
     * @param zipBytes   zip字节数组
     * @param managedDir 托管技能目录
     * @param overwrite  是否覆盖已有
     * @return 安装结果
     */
    public InstallResult installFromZip(String skillName, byte[] zipBytes,
                                         Path managedDir, boolean overwrite) {
        skillName = sanitizeSkillName(skillName);

        if (zipBytes == null || zipBytes.length == 0) {
            return InstallResult.fail(skillName, "Empty zip archive");
        }
        if (zipBytes.length > MAX_ZIP_BYTES) {
            return InstallResult.fail(skillName, "Zip archive too large (max 50MB)");
        }

        try {
            Path skillDir = managedDir.resolve(skillName);
            Path skillMdPath = skillDir.resolve("SKILL.md");

            if (!overwrite && Files.isDirectory(skillDir) && Files.isRegularFile(skillMdPath)) {
                return InstallResult.fail(skillName, "Already installed (use update to overwrite)");
            }

            // 如果覆盖则删除旧目录
            if (overwrite && Files.isDirectory(skillDir)) {
                deleteDirectoryTree(skillDir);
            }
            Files.createDirectories(skillDir);

            // 解压zip并防止路径遍历攻击
            int entryCount = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entryCount++ > MAX_ZIP_ENTRIES) {
                        log.warn("Too many zip entries for skill '{}'", skillName);
                        break;
                    }

                    String entryName = sanitizeZipPath(entry.getName(), skillName);
                    if (entryName == null) {
                        log.warn("Skipping suspicious zip entry: {}", entry.getName());
                        continue;
                    }

                    Path targetPath = skillDir.resolve(entryName).normalize();
                    // 安全检查：确保路径在skillDir内
                    if (!targetPath.startsWith(skillDir)) {
                        log.warn("Path traversal attempt in zip entry: {}", entry.getName());
                        continue;
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        try (OutputStream os = Files.newOutputStream(targetPath)) {
                            zis.transferTo(os);
                        }
                        log.debug("Extracted: {}", targetPath);
                    }
                    zis.closeEntry();
                }
            }

            if (!Files.isRegularFile(skillMdPath)) {
                return InstallResult.fail(skillName, "Zip does not contain SKILL.md at expected location");
            }

            log.info("Installed skill '{}' from zip to {}", skillName, skillDir);
            SkillEntry loaded = loader.loadSingle(skillMdPath, skillDir, SkillEntry.Source.MANAGED);
            if (loaded == null) {
                return InstallResult.fail(skillName, "Zip extracted but SKILL.md parse failed");
            }
            return InstallResult.ok(skillName, skillDir, loaded);

        } catch (IOException e) {
            log.error("Failed to install zip for skill '{}': {}", skillName, e.getMessage());
            return InstallResult.fail(skillName, "IO error: " + e.getMessage());
        }
    }

    // ─── 卸载 ────────────────────────────────────────────────────────────

    /**
     * 按名称卸载托管技能。
     * 只有MANAGED技能（~/.openclaw4j/skills/）可以被卸载。
     *
     * @param skillName 技能名称
     * @param managedDir 托管技能目录
     * @return 是否已删除，未找到则返回false
     */
    public boolean uninstall(String skillName, Path managedDir) {
        skillName = sanitizeSkillName(skillName);
        Path skillDir = managedDir.resolve(skillName);
        if (!Files.isDirectory(skillDir)) {
            log.warn("Skill '{}' not found in managed dir: {}", skillName, managedDir);
            return false;
        }
        try {
            deleteDirectoryTree(skillDir);
            log.info("Uninstalled skill '{}' from {}", skillName, skillDir);
            return true;
        } catch (IOException e) {
            log.error("Failed to uninstall skill '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    /**
     * 从商店更新已安装技能到最新版本。
     *
     * @param storeSkillId 商店技能ID
     * @param managedDir   托管技能目录
     * @return 安装结果
     */
    public InstallResult update(String storeSkillId, Path managedDir) {
        return installById(storeSkillId, managedDir, true);
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 清理技能名称为安全的目录名。
     * 只保留字母数字、短横线、下划线。空格替换为短横线。
     *
     * @param name 原始名称
     * @return 安全的目录名
     */
    public static String sanitizeSkillName(String name) {
        if (name == null || name.isBlank()) return "unknown-skill";
        return name.trim()
            .toLowerCase()
            .replaceAll("\\s+", "-")
            .replaceAll("[^a-z0-9\\-_]", "")
            .replaceAll("-{2,}", "-");
    }

    /**
     * 清理zip条目路径以防止路径遍历攻击。
     * 如果路径可疑则返回null。
     * 去除zip中任何顶层目录前缀。
     *
     * @param entryName  条目名称
     * @param skillName  技能名称
     * @return 清理后的路径，或null如果可疑
     */
    private static String sanitizeZipPath(String entryName, String skillName) {
        if (entryName == null || entryName.isBlank()) return null;

        // 拒绝绝对路径
        if (entryName.startsWith("/") || entryName.startsWith("\\")) return null;

        // 拒绝路径遍历
        if (entryName.contains("..")) return null;

        // 去除开头的"skillname/"前缀（GitHub zip常见）
        String normalized = entryName.replace('\\', '/');
        int firstSlash = normalized.indexOf('/');
        if (firstSlash > 0 && firstSlash < normalized.length() - 1) {
            // 检查第一段是否像版本前缀（如 "skill-name-1.0/"）
            String firstSeg = normalized.substring(0, firstSlash);
            if (firstSeg.matches("[a-zA-Z0-9\\-_.]+")) {
                normalized = normalized.substring(firstSlash + 1);
            }
        }

        return normalized.isBlank() ? null : normalized;
    }

    /**
     * 递归删除目录树。
     *
     * @param dir 要删除的目录
     * @throws IOException IO异常
     */
    private static void deleteDirectoryTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path subDir, IOException exc) throws IOException {
                Files.delete(subDir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
