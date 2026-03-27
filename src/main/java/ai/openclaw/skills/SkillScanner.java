package ai.openclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * 技能目录和SKILL.md脚本文件的安全扫描器。
 *
 * <p>参考项目中{@code src/security/skill-scanner.ts}的逻辑实现。
 * 扫描与技能捆绑的JavaScript、Python、Shell和其他脚本文件，检测：
 * <ul>
 *   <li>危险的Shell/进程执行（{@code dangerous-exec}）</li>
 *   <li>动态代码执行（{@code dynamic-code-execution}）</li>
 *   <li>挖矿指标（{@code crypto-mining}）</li>
 *   <li>可疑WebSocket连接（{@code suspicious-network}）</li>
 *   <li>数据泄露模式（{@code potential-exfiltration}）</li>
 *   <li>混淆代码（{@code obfuscated-code}）</li>
 *   <li>环境变量收集（{@code env-harvesting}）</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   SkillScanner.ScanSummary summary = SkillScanner.scanDirectory(skillDir);
 *   if (summary.critical > 0) { // 阻止安装 }
 * </pre>
 */
public class SkillScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillScanner.class);

    // ─── 类型 ────────────────────────────────────────────────────────────────

    /**
     * 严重程度枚举。
     */
    public enum Severity { INFO, WARN, CRITICAL }

    /**
     * 扫描发现项。
     */
    public static class Finding {
        /** 规则ID */
        public final String ruleId;
        /** 严重程度 */
        public final Severity severity;
        /** 文件路径 */
        public final String file;
        /** 行号 */
        public final int line;
        /** 消息 */
        public final String message;
        /** 证据 */
        public final String evidence;

        /**
         * 构造函数。
         *
         * @param ruleId   规则ID
         * @param severity 严重程度
         * @param file     文件路径
         * @param line     行号
         * @param message  消息
         * @param evidence 证据
         */
        public Finding(String ruleId, Severity severity, String file, int line,
                       String message, String evidence) {
            this.ruleId   = ruleId;
            this.severity = severity;
            this.file     = file;
            this.line     = line;
            this.message  = message;
            this.evidence = evidence;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (line %d in %s): %s — evidence: %s",
                severity, ruleId, line, file, message, evidence);
        }
    }

    /**
     * 扫描摘要。
     */
    public static class ScanSummary {
        /** 扫描的文件数 */
        public int scannedFiles;
        /** 严重问题数 */
        public int critical;
        /** 警告数 */
        public int warn;
        /** 信息数 */
        public int info;
        /** 发现项列表 */
        public final List<Finding> findings = new ArrayList<>();

        /**
         * 是否有阻止性发现。
         *
         * @return 是否有严重问题
         */
        public boolean hasBlockingFindings() { return critical > 0; }

        /**
         * 生成简短的人类可读报告。
         *
         * @return 报告字符串
         */
        public String toReport() {
            if (findings.isEmpty()) return "No security findings.";
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Scanned %d file(s): %d critical, %d warn, %d info\n",
                scannedFiles, critical, warn, info));
            for (Finding f : findings) {
                sb.append("  ").append(f).append("\n");
            }
            return sb.toString().trim();
        }
    }

    // ─── 可扫描的扩展名 ─────────────────────────────────────────────────

    private static final Set<String> SCANNABLE_EXTENSIONS = Set.of(
        ".js", ".mjs", ".cjs", ".ts", ".mts", ".cts", ".jsx", ".tsx",
        ".py", ".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd"
    );

    private static final int DEFAULT_MAX_FILES      = 500;
    private static final int DEFAULT_MAX_FILE_BYTES = 1024 * 1024; // 1 MB
    private static final int MAX_EVIDENCE_LEN       = 120;

    // ─── 行规则（每个规则每个文件触发一次） ─────────────────────────────

    /**
     * 行规则定义。
     */
    private static final class LineRule {
        final String ruleId;
        final Severity severity;
        final String message;
        final Pattern pattern;
        /** 如果非null，整个源码也必须匹配此规则才会触发 */
        final Pattern requiresContext;

        LineRule(String ruleId, Severity severity, String message, String pattern,
                 String requiresContext) {
            this.ruleId          = ruleId;
            this.severity        = severity;
            this.message         = message;
            this.pattern         = Pattern.compile(pattern);
            this.requiresContext = requiresContext != null
                                   ? Pattern.compile(requiresContext, Pattern.CASE_INSENSITIVE)
                                   : null;
        }
    }

    private static final List<LineRule> LINE_RULES = List.of(
        // JS/TS child_process 执行
        new LineRule(
            "dangerous-exec", Severity.CRITICAL,
            "Shell command execution detected (child_process)",
            "\\b(exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\(",
            "child_process"
        ),
        // Python subprocess / os.system
        new LineRule(
            "dangerous-exec", Severity.CRITICAL,
            "Shell command execution detected (subprocess/os.system)",
            "\\b(subprocess\\.run|subprocess\\.call|subprocess\\.Popen|os\\.system|os\\.popen)\\s*\\(",
            null
        ),
        // Shell脚本执行辅助
        new LineRule(
            "dangerous-exec", Severity.WARN,
            "Shell execution primitive detected",
            "\\b(exec|eval)\\s+[`\"']",
            null
        ),
        // JS中的eval / new Function
        new LineRule(
            "dynamic-code-execution", Severity.CRITICAL,
            "Dynamic code execution detected",
            "\\beval\\s*\\(|new\\s+Function\\s*\\(",
            null
        ),
        // Python exec()
        new LineRule(
            "dynamic-code-execution", Severity.CRITICAL,
            "Dynamic code execution detected (Python exec/eval)",
            "\\bexec\\s*\\(|\\bcompile\\s*\\(",
            null
        ),
        // 挖矿
        new LineRule(
            "crypto-mining", Severity.CRITICAL,
            "Possible crypto-mining reference detected",
            "stratum\\+tcp|stratum\\+ssl|coinhive|cryptonight|xmrig",
            null
        ),
        // 非标准端口的WebSocket
        new LineRule(
            "suspicious-network", Severity.WARN,
            "WebSocket connection to non-standard port",
            "new\\s+WebSocket\\s*\\(\\s*[\"']wss?://[^\"']*:(\\d+)",
            null
        )
    );

    private static final Set<Integer> STANDARD_PORTS = Set.of(80, 443, 8080, 8443, 3000);

    // ─── 源码规则（全文件匹配，每个ruleId+message触发一次） ────────

    /**
     * 源码规则定义。
     */
    private static final class SourceRule {
        final String ruleId;
        final Severity severity;
        final String message;
        final Pattern pattern;
        final Pattern requiresContext;

        SourceRule(String ruleId, Severity severity, String message,
                   String pattern, String requiresContext) {
            this.ruleId          = ruleId;
            this.severity        = severity;
            this.message         = message;
            this.pattern         = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            this.requiresContext = requiresContext != null
                                   ? Pattern.compile(requiresContext, Pattern.CASE_INSENSITIVE)
                                   : null;
        }
    }

    private static final List<SourceRule> SOURCE_RULES = List.of(
        new SourceRule(
            "potential-exfiltration", Severity.WARN,
            "File read combined with network send — possible data exfiltration",
            "readFileSync|readFile|open\\s*\\([^)]*['\"]r['\"]",
            "fetch|requests\\.post|http\\.request|urllib"
        ),
        new SourceRule(
            "obfuscated-code", Severity.WARN,
            "Hex-encoded string sequence detected (possible obfuscation)",
            "(\\\\x[0-9a-fA-F]{2}){6,}",
            null
        ),
        new SourceRule(
            "obfuscated-code", Severity.WARN,
            "Large base64 payload with decode call detected (possible obfuscation)",
            "(?:atob|Buffer\\.from|base64\\.b64decode)\\s*\\(\\s*[\"'][A-Za-z0-9+/=]{200,}[\"']",
            null
        ),
        new SourceRule(
            "env-harvesting", Severity.CRITICAL,
            "Environment variable access combined with network send — possible credential harvesting",
            "process\\.env|os\\.environ|getenv\\s*\\(",
            "fetch|requests\\.|http\\.request|urllib\\.request"
        )
    );

    // ─── 公共API ───────────────────────────────────────────────────────────

    /**
     * 扫描技能目录中的所有脚本文件。
     *
     * @param skillDir 技能根目录（包含SKILL.md）
     * @return 扫描摘要；检查{@link ScanSummary#hasBlockingFindings()}决定是否通过
     */
    public static ScanSummary scanDirectory(Path skillDir) {
        return scanDirectory(skillDir, DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * 扫描技能目录中的所有脚本文件（可配置限制）。
     *
     * @param skillDir     技能根目录
     * @param maxFiles     最大文件数
     * @param maxFileBytes 最大文件字节数
     * @return 扫描摘要
     */
    public static ScanSummary scanDirectory(Path skillDir, int maxFiles, int maxFileBytes) {
        ScanSummary summary = new ScanSummary();
        if (!Files.isDirectory(skillDir)) return summary;

        List<Path> files = collectScannableFiles(skillDir, maxFiles);
        for (Path file : files) {
            try {
                long size = Files.size(file);
                if (size > maxFileBytes) {
                    log.debug("SkillScanner: skipping large file ({} bytes): {}", size, file);
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                List<Finding> fileFindings = scanSource(source, file.toString());
                if (!fileFindings.isEmpty()) {
                    summary.scannedFiles++;
                    for (Finding f : fileFindings) {
                        summary.findings.add(f);
                        switch (f.severity) {
                            case CRITICAL -> summary.critical++;
                            case WARN     -> summary.warn++;
                            case INFO     -> summary.info++;
                        }
                    }
                } else {
                    summary.scannedFiles++;
                }
            } catch (IOException e) {
                log.warn("SkillScanner: could not read {}: {}", file, e.getMessage());
            }
        }
        return summary;
    }

    /**
     * 扫描单个源码字符串（如一个脚本文件的内容）。
     *
     * @param source   文件内容
     * @param filePath 用于显示的文件路径（可以是相对或绝对路径）
     * @return 发现项列表（干净则为空）
     */
    public static List<Finding> scanSource(String source, String filePath) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        Set<String> matchedLineRules = new HashSet<>();

        // ── 行规则 ────────────────────────────────────────────────────────
        for (LineRule rule : LINE_RULES) {
            if (matchedLineRules.contains(rule.ruleId)) continue;

            // 如果全文件上下文要求未满足则跳过
            if (rule.requiresContext != null && !rule.requiresContext.matcher(source).find()) {
                continue;
            }

            for (int i = 0; i < lines.length; i++) {
                Matcher m = rule.pattern.matcher(lines[i]);
                if (!m.find()) continue;

                // 特殊处理：suspicious-network — 仅标记非标准端口
                if ("suspicious-network".equals(rule.ruleId)) {
                    try {
                        int port = Integer.parseInt(m.group(1));
                        if (STANDARD_PORTS.contains(port)) continue;
                    } catch (Exception ignore) {}
                }

                findings.add(new Finding(
                    rule.ruleId, rule.severity, filePath, i + 1,
                    rule.message, truncateEvidence(lines[i].trim())
                ));
                matchedLineRules.add(rule.ruleId);
                break; // 每个行规则每个文件只产生一个发现
            }
        }

        // ── 源码规则 ──────────────────────────────────────────────────────
        Set<String> matchedSourceKeys = new HashSet<>();
        for (SourceRule rule : SOURCE_RULES) {
            String key = rule.ruleId + "::" + rule.message;
            if (matchedSourceKeys.contains(key)) continue;

            if (!rule.pattern.matcher(source).find()) continue;
            if (rule.requiresContext != null && !rule.requiresContext.matcher(source).find()) {
                continue;
            }

            // 查找第一个匹配行作为证据
            int matchLine = 1;
            String matchEvidence = source.length() > MAX_EVIDENCE_LEN
                                   ? source.substring(0, MAX_EVIDENCE_LEN) : source;
            for (int i = 0; i < lines.length; i++) {
                if (rule.pattern.matcher(lines[i]).find()) {
                    matchLine = i + 1;
                    matchEvidence = lines[i].trim();
                    break;
                }
            }

            findings.add(new Finding(
                rule.ruleId, rule.severity, filePath, matchLine,
                rule.message, truncateEvidence(matchEvidence)
            ));
            matchedSourceKeys.add(key);
        }

        return findings;
    }

    // ─── 辅助方法 ──────────────────────────────────────────────────────────────

    /**
     * 收集可扫描的文件。
     *
     * @param root    根目录
     * @param maxFiles 最大文件数
     * @return 文件列表
     */
    private static List<Path> collectScannableFiles(Path root, int maxFiles) {
        List<Path> result = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // 跳过隐藏目录和node_modules
                    if (name.startsWith(".") || name.equals("node_modules")
                            || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (result.size() >= maxFiles) return FileVisitResult.TERMINATE;
                    String name = file.getFileName().toString().toLowerCase();
                    String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                    if (SCANNABLE_EXTENSIONS.contains(ext)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("SkillScanner: walk failed for {}: {}", root, e.getMessage());
        }
        return result;
    }

    /**
     * 截断证据字符串。
     *
     * @param evidence 证据字符串
     * @return 截断后的字符串
     */
    private static String truncateEvidence(String evidence) {
        if (evidence == null) return "";
        if (evidence.length() <= MAX_EVIDENCE_LEN) return evidence;
        return evidence.substring(0, MAX_EVIDENCE_LEN) + "…";
    }
}
