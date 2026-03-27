package ai.openclaw.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Skills 子系统的中央管理器。
 *
 * 职责：
 *  - 从所有来源加载技能（bundled / managed / workspace / extra）
 *  - 通过文件系统监听实现热重载（可选）
 *  - 委托 SkillHubClient 处理商店交互
 *  - 委托 SkillInstaller 处理安装/卸载/更新
 *  - 为代理构建系统提示词技能块
 *  - 应用配置中的每个技能启用/禁用覆盖
 *
 * 技能优先级（高优先级覆盖低优先级的同名冲突）：
 *   EXTRA < BUNDLED < MANAGED < WORKSPACE
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final SkillsConfig config;
    private final SkillLoader loader;
    private final SkillHubClient hubClient;
    private final SkillInstaller installer;
    private final Path managedSkillsDir;

    /** 内存注册表：skillKey → SkillEntry（高优先级覆盖低优先级） */
    private final Map<String, SkillEntry> registry = new ConcurrentHashMap<>();

    /** 所有技能目录的最后修改时间戳（用于变更检测） */
    private volatile long lastMaxModified = 0L;

    /** 后台监听执行器 */
    private final ScheduledExecutorService watchExecutor;

    /** workspace 目录，用于 workspace 来源的技能 */
    private volatile Path workspaceDir;

    // ─── 生命周期 ─────────────────────────────────────────────────────────────

    /**
     * 构造函数。
     *
     * @param config      技能配置
     * @param mapper      Jackson ObjectMapper
     * @param workspaceDir 工作区目录
     */
    public SkillManager(SkillsConfig config, ObjectMapper mapper, Path workspaceDir) {
        this.config = config;
        this.workspaceDir = workspaceDir;
        this.loader = new SkillLoader(config.maxSkillsPerSource, config.maxSkillFileBytes);
        this.hubClient = new SkillHubClient(config.storeUrl, mapper);
        this.installer = new SkillInstaller(hubClient);
        this.managedSkillsDir = SkillLoader.getManagedSkillsDir();
        this.watchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 初始化：从所有来源加载技能。
     * 启动时调用一次。
     */
    public void start() {
        reloadAll(true); // 启动时强制加载

        if (config.watch) {
            // 每5秒轮询变更 — 仅在文件实际变更时重新加载
            watchExecutor.scheduleWithFixedDelay(() -> reloadAll(false), 5, 5, TimeUnit.SECONDS);
            log.info("SkillManager hot-reload watcher started (every 5s)");
        }

        log.info("SkillManager started: {} skills loaded (store={})", registry.size(), config.storeUrl);
    }

    /**
     * 停止管理器，关闭后台监听器。
     */
    public void stop() {
        watchExecutor.shutdownNow();
    }

    // ─── 加载 ───────────────────────────────────────────────────────────────────────

    /**
     * 重新加载所有来源的技能。线程安全。
     * 当从监听器调用时，如果文件未变更则跳过重新加载。
     */
    public synchronized void reloadAll() {
        reloadAll(false);
    }

    /**
     * 内部重新加载方法。如果 {@code force=false}（监听轮询），则无文件变更时跳过。
     */
    private void reloadAll(boolean force) {
        // ── 变更检测（无变更则跳过） ──────────────────────
        if (!force) {
            long maxMod = computeMaxModified();
            if (maxMod == lastMaxModified && lastMaxModified != 0) {
                return; // 无变更，静默跳过
            }
            lastMaxModified = maxMod;
        }

        Map<String, SkillEntry> fresh = new LinkedHashMap<>();

        // 1. 额外目录（最低优先级）
        for (String extraDir : config.extraDirs) {
            Path p = Paths.get(extraDir);
            for (SkillEntry e : loader.loadFromDir(p, SkillEntry.Source.EXTRA)) {
                fresh.put(e.getKey(), e);
            }
        }

        // 2. 捆绑技能（JAR旁边）
        Path bundledDir = SkillLoader.getBundledSkillsDir();
        if (bundledDir != null) {
            for (SkillEntry e : loader.loadFromDir(bundledDir, SkillEntry.Source.BUNDLED)) {
                fresh.put(e.getKey(), e);
            }
        }

        // 3. 托管技能（~/.openclaw4j/skills/）
        for (SkillEntry e : loader.loadFromDir(managedSkillsDir, SkillEntry.Source.MANAGED)) {
            fresh.put(e.getKey(), e);
        }

        // 4. 工作区技能（<workspaceDir>/skills/）— 最高优先级
        if (workspaceDir != null) {
            for (SkillEntry e : loader.loadFromDir(workspaceDir, SkillEntry.Source.WORKSPACE)) {
                fresh.put(e.getKey(), e);
            }
        }

        // 应用配置中的启用/禁用覆盖
        applyConfigOverrides(fresh);

        // 交换注册表
        registry.clear();
        registry.putAll(fresh);

        log.info("Skills reloaded: {} total (extra={}, bundled={}, managed={}, workspace={})",
            registry.size(),
            countBySource(fresh, SkillEntry.Source.EXTRA),
            countBySource(fresh, SkillEntry.Source.BUNDLED),
            countBySource(fresh, SkillEntry.Source.MANAGED),
            countBySource(fresh, SkillEntry.Source.WORKSPACE)
        );
    }

    private void applyConfigOverrides(Map<String, SkillEntry> entries) {
        if (config.entries == null) return;
        config.entries.forEach((key, entryConfig) -> {
            SkillEntry entry = entries.get(key);
            if (entry != null && entryConfig.enabled != null) {
                entry.enabled = entryConfig.enabled;
            }
        });
    }

    /**
     * 返回所有技能来源目录的最大最后修改时间戳。
     * 用于检测是否需要重新加载。
     */
    private long computeMaxModified() {
        long max = 0L;
        List<Path> dirs = new ArrayList<>();

        for (String extraDir : config.extraDirs) dirs.add(Paths.get(extraDir));
        Path bundled = SkillLoader.getBundledSkillsDir();
        if (bundled != null) dirs.add(bundled);
        dirs.add(managedSkillsDir);
        if (workspaceDir != null) dirs.add(workspaceDir);

        for (Path dir : dirs) {
            if (!Files.exists(dir)) continue;
            try (var stream = Files.walk(dir, 2)) {
                long dirMax = stream
                    .filter(p -> p.toString().endsWith("SKILL.md"))
                    .mapToLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    })
                    .max().orElse(0L);
                if (dirMax > max) max = dirMax;
            } catch (IOException e) {
                // 忽略不可读目录
            }
        }
        return max;
    }

    // ─── 注册表访问 ──────────────────────────────────────────────────────

    /** 获取所有技能（包括已禁用的） */
    public List<SkillEntry> getAllSkills() {
        return new ArrayList<>(registry.values());
    }

    /** 获取已启用的技能 */
    public List<SkillEntry> getEnabledSkills() {
        return registry.values().stream()
            .filter(e -> e.enabled)
            .collect(Collectors.toList());
    }

    /** 根据 key 获取单个技能 */
    public Optional<SkillEntry> getSkill(String key) {
        return Optional.ofNullable(registry.get(key));
    }

    /** 检查技能是否已安装（作为托管技能） */
    public boolean isInstalled(String skillName) {
        Path skillDir = managedSkillsDir.resolve(SkillInstaller.sanitizeSkillName(skillName));
        return Files.isDirectory(skillDir) && Files.isRegularFile(skillDir.resolve("SKILL.md"));
    }

    /**
     * 获取各来源的技能数量。
     */
    public Map<String, Integer> getSkillCountBySource() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SkillEntry.Source src : SkillEntry.Source.values()) {
            counts.put(src.name().toLowerCase(), countBySource(registry, src));
        }
        return counts;
    }

    // ─── 系统提示词集成 ────────────────────────────────────────────

    /**
     * 为代理构建系统提示词块，描述可用技能。
     *
     * @param agentSkillKeys   代理配置的技能 key 列表（可为 null 或 "all"）
     * @return 格式化的字符串，追加到代理的系统提示词，或空字符串
     */
    public String buildSkillsPromptBlock(List<String> agentSkillKeys) {
        List<SkillEntry> skills = selectSkillsForAgent(agentSkillKeys);
        if (skills.isEmpty()) return "";

        // Limit by count and total chars
        List<SkillEntry> selected = new ArrayList<>();
        int totalChars = 0;
        for (SkillEntry entry : skills) {
            String block = formatSkillForPrompt(entry);
            if (totalChars + block.length() > config.maxSkillsPromptChars) break;
            selected.add(entry);
            totalChars += block.length();
            if (selected.size() >= config.maxSkillsInPrompt) break;
        }

        if (selected.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available Skills\n\n");
        sb.append("You have access to the following skills. Each skill provides special capabilities ");
        sb.append("and instructions you should follow when the user's request relates to it.\n\n");

        for (SkillEntry entry : selected) {
            sb.append(formatSkillForPrompt(entry));
        }

        return sb.toString();
    }

    private List<SkillEntry> selectSkillsForAgent(List<String> agentSkillKeys) {
        List<SkillEntry> enabled = getEnabledSkills();

        // If no skills configured, or "all" requested, return all enabled
        if (agentSkillKeys == null || agentSkillKeys.isEmpty()
            || agentSkillKeys.contains("all")) {
            return enabled;
        }

        // Filter to requested keys only
        Set<String> keySet = new HashSet<>(agentSkillKeys);
        return enabled.stream()
            .filter(e -> keySet.contains(e.getKey()) || keySet.contains(e.meta.name))
            .collect(Collectors.toList());
    }

    private String formatSkillForPrompt(SkillEntry entry) {
        StringBuilder sb = new StringBuilder();
        String emoji = entry.meta.emoji != null ? entry.meta.emoji + " " : "";
        sb.append("### ").append(emoji).append(entry.meta.name).append("\n");
        if (entry.meta.description != null) {
            sb.append(entry.meta.description).append("\n\n");
        }
        if (entry.getBody() != null && !entry.getBody().isBlank()) {
            sb.append(entry.getBody().trim()).append("\n\n");
        }
        return sb.toString();
    }

    // ─── 商店操作 ─────────────────────────────────────────────────────

    /**
     * 在商店中搜索技能。
     *
     * @param query    搜索关键词
     * @param category 分类过滤
     * @param tag      标签过滤
     * @param page     页码
     * @param pageSize 每页大小
     * @return 分页技能列表
     */
    public SkillHubClient.StoreSkillPage searchStore(String query, String category,
                                                      String tag, int page, int pageSize) {
        return hubClient.searchSkills(query, category, tag, page, pageSize);
    }

    /**
     * 从商店获取精选技能。
     *
     * @return 精选技能列表
     */
    public List<SkillHubClient.StoreSkill> getFeaturedSkills() {
        return hubClient.getFeaturedSkills();
    }

    /**
     * 从商店获取分类列表。
     *
     * @return 分类列表
     */
    public List<SkillHubClient.StoreCategory> getStoreCategories() {
        return hubClient.getCategories();
    }

    /**
     * 检查商店是否可达。
     *
     * @return 是否可达
     */
    public boolean isStoreReachable() {
        return hubClient.isReachable();
    }

    // ─── 安装/卸载/更新 ─────────────────────────────────────────────────

    /**
     * 通过商店ID从商店安装技能，然后重新加载注册表。
     * 安装前会进行安全扫描。
     *
     * @param storeSkillId 商店技能ID
     * @return 安装结果
     */
    public SkillInstaller.InstallResult install(String storeSkillId) {
        try {
            Files.createDirectories(managedSkillsDir);
        } catch (IOException e) {
            return SkillInstaller.InstallResult.fail(storeSkillId, "Cannot create skills dir: " + e.getMessage());
        }

        SkillInstaller.InstallResult result = installer.installById(storeSkillId, managedSkillsDir, false);
        if (result.success) {
            // 对新安装的技能目录进行安全扫描
            Path installedDir = managedSkillsDir.resolve(SkillInstaller.sanitizeSkillName(storeSkillId));
            SkillScanner.ScanSummary scan = SkillScanner.scanDirectory(installedDir);
            if (scan.hasBlockingFindings()) {
                // 回滚：卸载刚安装的技能
                installer.uninstall(storeSkillId, managedSkillsDir);
                log.warn("SkillScanner blocked install of '{}': {}", storeSkillId, scan.toReport());
                return SkillInstaller.InstallResult.fail(storeSkillId,
                    "Security scan failed (P0 findings). Install aborted.\n" + scan.toReport());
            }
            if (scan.warn > 0) {
                log.warn("SkillScanner: {} warn finding(s) for '{}' — install allowed with warnings.\n{}",
                    scan.warn, storeSkillId, scan.toReport());
            }
            reloadAll();
        }
        return result;
    }

    /**
     * 直接从StoreSkill对象安装。
     * 安装前会进行安全扫描。
     *
     * @param storeSkill 商店技能信息
     * @return 安装结果
     */
    public SkillInstaller.InstallResult installFromStore(SkillHubClient.StoreSkill storeSkill) {
        try {
            Files.createDirectories(managedSkillsDir);
        } catch (IOException e) {
            return SkillInstaller.InstallResult.fail(storeSkill.name, "Cannot create skills dir: " + e.getMessage());
        }
        SkillInstaller.InstallResult result = installer.installFromStore(storeSkill, managedSkillsDir, false);
        if (result.success) {
            Path installedDir = managedSkillsDir.resolve(SkillInstaller.sanitizeSkillName(storeSkill.name));
            SkillScanner.ScanSummary scan = SkillScanner.scanDirectory(installedDir);
            if (scan.hasBlockingFindings()) {
                installer.uninstall(storeSkill.name, managedSkillsDir);
                log.warn("SkillScanner blocked install of '{}': {}", storeSkill.name, scan.toReport());
                return SkillInstaller.InstallResult.fail(storeSkill.name,
                    "Security scan failed (P0 findings). Install aborted.\n" + scan.toReport());
            }
            if (scan.warn > 0) {
                log.warn("SkillScanner: {} warn finding(s) for '{}' — install allowed with warnings.\n{}",
                    scan.warn, storeSkill.name, scan.toReport());
            }
            reloadAll();
        }
        return result;
    }

    /**
     * 从SKILL.md原始文本安装技能（手动安装）。
     * 提交前会对写入内容进行安全扫描。
     *
     * @param skillName      技能目录的名称/键
     * @param skillMdContent SKILL.md原始内容
     * @return 安装结果
     */
    public SkillInstaller.InstallResult installManual(String skillName, String skillMdContent) {
        try {
            Files.createDirectories(managedSkillsDir);
        } catch (IOException e) {
            return SkillInstaller.InstallResult.fail(skillName, "Cannot create skills dir: " + e.getMessage());
        }

        // 预扫描SKILL.md内容本身是否有嵌入的危险模式
        // （边缘情况：markdown正文中的嵌入式脚本片段）
        java.util.List<SkillScanner.Finding> mdFindings = SkillScanner.scanSource(skillMdContent, "SKILL.md");
        long criticalMd = mdFindings.stream()
            .filter(f -> f.severity == SkillScanner.Severity.CRITICAL).count();
        if (criticalMd > 0) {
            String report = mdFindings.stream().map(SkillScanner.Finding::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
            log.warn("SkillScanner blocked manual install of '{}': {}", skillName, report);
            return SkillInstaller.InstallResult.fail(skillName,
                "Security scan of SKILL.md content failed (P0 findings). Install aborted.\n" + report);
        }

        SkillInstaller.InstallResult result = installer.installSkillMd(skillName, skillMdContent, managedSkillsDir, false);
        if (result.success) {
            // 同时扫描已安装目录（可能有scripts/子文件）
            Path installedDir = managedSkillsDir.resolve(SkillInstaller.sanitizeSkillName(skillName));
            SkillScanner.ScanSummary scan = SkillScanner.scanDirectory(installedDir);
            if (scan.hasBlockingFindings()) {
                installer.uninstall(skillName, managedSkillsDir);
                log.warn("SkillScanner blocked manual install of '{}': {}", skillName, scan.toReport());
                return SkillInstaller.InstallResult.fail(skillName,
                    "Security scan failed (P0 findings). Install aborted.\n" + scan.toReport());
            }
            reloadAll();
        }
        return result;
    }

    /**
     * 通过技能键卸载托管技能。
     *
     * @param skillKey 技能键
     * @return 是否已删除
     */
    public boolean uninstall(String skillKey) {
        boolean removed = installer.uninstall(skillKey, managedSkillsDir);
        if (removed) reloadAll();
        return removed;
    }

    /**
     * 从商店更新托管技能（重新下载最新版本）。
     *
     * @param storeSkillId 商店技能ID
     * @return 安装结果
     */
    public SkillInstaller.InstallResult update(String storeSkillId) {
        SkillInstaller.InstallResult result = installer.update(storeSkillId, managedSkillsDir);
        if (result.success) reloadAll();
        return result;
    }

    // ─── 辅助方法 ──────────────────────────────────────────────────────────────

    private static int countBySource(Map<String, SkillEntry> entries, SkillEntry.Source source) {
        return (int) entries.values().stream().filter(e -> e.source == source).count();
    }

    /**
     * 获取托管技能目录。
     *
     * @return 托管技能目录路径
     */
    public Path getManagedSkillsDir() { return managedSkillsDir; }

    /**
     * 获取商店URL。
     *
     * @return 商店URL
     */
    public String getStoreUrl() { return config.storeUrl; }

    /**
     * 设置工作区目录。
     *
     * @param workspaceDir 工作区目录
     */
    public void setWorkspaceDir(Path workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    // ─── 启用/禁用 ─────────────────────────────────────────────────────

    /**
     * 通过技能键启用技能（仅运行时 — 除非更新配置否则重启后失效）。
     *
     * @param skillKey 技能键（如"my-skill"）
     * @return 如果找到并启用技能则返回true；未找到返回false
     */
    public synchronized boolean enableSkill(String skillKey) {
        SkillEntry entry = registry.get(skillKey);
        if (entry == null) return false;
        entry.enabled = true;
        log.info("Skill enabled: {}", skillKey);
        return true;
    }

    /**
     * 通过技能键禁用技能（仅运行时）。
     *
     * @param skillKey 技能键
     * @return 如果找到并禁用技能则返回true；未找到返回false
     */
    public synchronized boolean disableSkill(String skillKey) {
        SkillEntry entry = registry.get(skillKey);
        if (entry == null) return false;
        entry.enabled = false;
        log.info("Skill disabled: {}", skillKey);
        return true;
    }

    /**
     * 对托管技能目录运行安全扫描，不进行任何更改。
     * 用于API向UI暴露扫描结果。
     *
     * @param skillKey 要扫描的技能键
     * @return 扫描摘要，技能未找到返回null
     */
    public SkillScanner.ScanSummary scanSkill(String skillKey) {
        SkillEntry entry = registry.get(skillKey);
        if (entry == null) return null;
        Path skillDir = managedSkillsDir.resolve(SkillInstaller.sanitizeSkillName(skillKey));
        if (!Files.isDirectory(skillDir)) return null;
        return SkillScanner.scanDirectory(skillDir);
    }

    /**
     * 为API响应生成技能摘要列表。
     *
     * @return 技能摘要列表
     */
    public List<Map<String, Object>> toApiList() {
        return registry.values().stream().map(entry -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", entry.getKey());
            m.put("name", entry.meta.name);
            m.put("description", entry.meta.description);
            m.put("source", entry.source.name().toLowerCase());
            m.put("enabled", entry.enabled);
            m.put("version", entry.meta.version);
            m.put("author", entry.meta.author);
            m.put("homepage", entry.meta.homepage);
            m.put("emoji", entry.meta.emoji);
            m.put("tags", entry.meta.tags);
            m.put("os", entry.meta.os);
            m.put("path", entry.getDisplayPath());
            m.put("installState", entry.installState.name().toLowerCase());
            return m;
        }).collect(Collectors.toList());
    }
}
