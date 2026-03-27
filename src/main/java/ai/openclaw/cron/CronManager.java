package ai.openclaw.cron;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.openclaw.OpenClaw4j;
import ai.openclaw.agents.AgentManager;
import ai.openclaw.config.ConfigManager;
import ai.openclaw.config.OpenClaw4jConfig;
import ai.openclaw.gateway.GatewaySessionRegistry;

/**
 * 定时任务管理器。
 * <p>
 * 管理定时任务调度器，在指定时间运行AI代理任务。
 * 使用Quartz Scheduler作为调度引擎。
 * </p>
 */
public class CronManager {

    private static final Logger log = LoggerFactory.getLogger(CronManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigManager configManager;
    private final AgentManager agentManager;
    private final GatewaySessionRegistry sessionRegistry;
    private Scheduler scheduler;

    /** 内存中的执行历史（最近50条） */
    private final Map<String, List<CronRun>> runHistory = new ConcurrentHashMap<>();

    /** 正在运行的任务（jobId -> CronRun） */
    private final Map<String, CronRun> runningJobs = new ConcurrentHashMap<>();

    /** 调度状态存储 */
    private final CronStateStore stateStore;
    /** 执行历史存储 */
    private final CronHistoryStore historyStore;

    /**
     * 构造定时任务管理器实例。
     *
     * @param configManager   配置管理器
     * @param agentManager    智能体管理器
     * @param sessionRegistry 会话注册表
     */
    public CronManager(ConfigManager configManager, AgentManager agentManager,
                       GatewaySessionRegistry sessionRegistry) {
        this.configManager = configManager;
        this.agentManager = agentManager;
        this.sessionRegistry = sessionRegistry;

        // 初始化存储
        Path cronDir = Paths.get(OpenClaw4j.DEFAULT_CONFIG_DIR, "cron");
        this.stateStore = new CronStateStore(cronDir);
        this.historyStore = new CronHistoryStore(cronDir);
    }

    /**
     * 启动调度器并调度所有已配置的任务。
     *
     * @throws SchedulerException 调度器启动失败时抛出
     */
    public void start() throws SchedulerException {
        // 加载调度状态
        stateStore.load();

        // 加载执行历史到内存
        for (OpenClaw4jConfig.CronJobConfig job : configManager.getConfig().cron) {
            List<CronRun> history = historyStore.load(job.id, 50);
            if (!history.isEmpty()) {
                runHistory.put(job.id, history);
            }
        }

        Properties props = new Properties();
        props.setProperty("org.quartz.threadPool.threadCount", "4");
        props.setProperty("org.quartz.scheduler.instanceName", "OpenClaw4j");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        SchedulerFactory sf = new StdSchedulerFactory(props);
        scheduler = sf.getScheduler();
        scheduler.start();

        // 调度所有已配置的任务，并处理 misfire
        for (OpenClaw4jConfig.CronJobConfig job : configManager.getConfig().cron) {
            if (job.enabled) {
                handleMisfire(job);
                scheduleJob(job);
            }
        }

        log.info("CronManager started, {} jobs scheduled",
            configManager.getConfig().cron.stream().filter(j -> j.enabled).count());
    }

    /**
     * 处理错过的触发（misfire）。
     * <p>
     * 检查任务的上次触发时间，如果错过了触发则立即执行一次。
     * </p>
     *
     * @param job 任务配置
     */
    private void handleMisfire(OpenClaw4jConfig.CronJobConfig job) {
        try {
            CronExpression cron = new CronExpression(job.schedule);
            JobState state = stateStore.get(job.id);

            // 获取上次应该触发的时间
            Date now = new Date();
            Date lastExpectedFire = cron.getTimeBefore(now);

            if (lastExpectedFire != null && state != null) {
                long lastFireTime = state.lastFireTime;
                long expectedTime = lastExpectedFire.getTime();

                // 如果上次触发时间早于应该触发的时间，说明错过了
                if (lastFireTime > 0 && lastFireTime < expectedTime) {
                    log.info("Detected misfire for job '{}', last fire: {}, expected: {}",
                        job.name, new Date(lastFireTime), lastExpectedFire);
                    // 立即执行一次
                    executeJobNow(job);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check misfire for job {}: {}", job.id, e.getMessage());
        }
    }

    /**
     * 立即执行一次任务（用于 misfire 恢复）。
     *
     * @param job 任务配置
     */
    private void executeJobNow(OpenClaw4jConfig.CronJobConfig job) {
        log.info("Executing missed job: {} ({})", job.name, job.id);
        CronRun run = new CronRun(job.id);
        run.startedAt = System.currentTimeMillis();
        run.jobName = job.name;

        // 记录为正在运行
        runningJobs.put(job.id, run);

        String sessionKey = "cron:" + job.id;

        agentManager.runAsync(job.agentId, sessionKey, job.prompt, new AgentManager.AgentCallback() {
            @Override public void onChunk(String chunk) {}

            @Override
            public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                run.completedAt = System.currentTimeMillis();
                run.status = "completed";
                run.output = fullResponse.length() > 1000 ? fullResponse.substring(0, 1000) + "..." : fullResponse;

                // 从运行列表移除
                runningJobs.remove(job.id);

                // 更新状态
                stateStore.updateFire(job.id, run.startedAt);
                // 持久化历史
                historyStore.append(run);
                // 更新内存缓存
                addRunHistory(runHistory, job.id, run);
            }

            @Override
            public void onError(String error) {
                run.completedAt = System.currentTimeMillis();
                run.status = "failed";
                run.error = error;

                // 从运行列表移除
                runningJobs.remove(job.id);

                stateStore.updateFire(job.id, run.startedAt);
                historyStore.append(run);
                addRunHistory(runHistory, job.id, run);
            }
        });
    }

    /**
     * 停止调度器。
     */
    public void stop() {
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown(false);
            }
        } catch (SchedulerException e) {
            log.error("Error stopping scheduler", e);
        }
    }

    /**
     * 调度一个定时任务。
     *
     * @param job 定时任务配置
     */
    public void scheduleJob(OpenClaw4jConfig.CronJobConfig job) {
        if (scheduler == null || job.schedule == null || job.schedule.isBlank()) return;

        try {
            JobDataMap dataMap = new JobDataMap();
            dataMap.put("jobConfig", job);
            dataMap.put("agentManager", agentManager);
            dataMap.put("sessionRegistry", sessionRegistry);
            dataMap.put("runHistory", runHistory);
            dataMap.put("runningJobs", runningJobs);
            dataMap.put("stateStore", stateStore);
            dataMap.put("historyStore", historyStore);

            JobDetail jobDetail = JobBuilder.newJob(CronJobExecutor.class)
                .withIdentity(job.id, "openclaw4j")
                .setJobData(dataMap)
                .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(job.id + "-trigger", "openclaw4j")
                .withSchedule(CronScheduleBuilder.cronSchedule(job.schedule)
                    .withMisfireHandlingInstructionFireAndProceed())
                .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled cron job: {} ({})", job.name, job.schedule);
        } catch (Exception e) {
            log.error("Failed to schedule job {}: {}", job.id, e.getMessage());
        }
    }

    /**
     * 取消调度指定的定时任务。
     *
     * @param jobId 任务ID
     */
    public void unscheduleJob(String jobId) {
        try {
            scheduler.deleteJob(JobKey.jobKey(jobId, "openclaw4j"));
            log.info("Removed cron job: {}", jobId);
        } catch (SchedulerException e) {
            log.error("Failed to remove job {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * 获取指定任务的执行历史记录。
     *
     * @param jobId 任务ID
     * @return 执行历史列表
     */
    public List<CronRun> getRunHistory(String jobId) {
        return runHistory.getOrDefault(jobId, new ArrayList<>());
    }

    /**
     * 获取正在运行的任务。
     *
     * @param jobId 任务ID
     * @return 正在运行的执行记录，不存在时返回null
     */
    public CronRun getRunningJob(String jobId) {
        return runningJobs.get(jobId);
    }

    /**
     * 获取所有正在运行的任务。
     *
     * @return 正在运行的任务映射
     */
    public Map<String, CronRun> getAllRunningJobs() {
        return new HashMap<>(runningJobs);
    }

    /**
     * 停止正在运行的任务。
     *
     * @param jobId 任务ID
     * @return 是否成功停止（false表示任务不存在或未在运行）
     */
    public boolean stopJob(String jobId) {
        CronRun run = runningJobs.get(jobId);
        if (run == null) {
            return false;
        }

        // 标记为已停止
        run.status = "stopped";
        run.completedAt = System.currentTimeMillis();
        run.error = "Job stopped by user";

        // 从运行列表中移除
        runningJobs.remove(jobId);

        // 记录到历史
        historyStore.append(run);
        addRunHistory(runHistory, jobId, run);

        log.info("Stopped cron job: {}", jobId);
        return true;
    }

    /**
     * 检查任务是否正在运行。
     *
     * @param jobId 任务ID
     * @return 是否正在运行
     */
    public boolean isRunning(String jobId) {
        return runningJobs.containsKey(jobId);
    }

    // ─── 任务执行器 ────────────────────────────────────────────────────────

    /**
     * 定时任务执行器。
     * <p>
     * Quartz Job实现，负责执行定时触发的AI代理任务。
     * </p>
     */
    public static class CronJobExecutor implements Job {
        /**
         * 执行定时任务。
         *
         * @param context 任务执行上下文
         */
        @Override
        public void execute(JobExecutionContext context) {
            JobDataMap data = context.getJobDetail().getJobDataMap();
            OpenClaw4jConfig.CronJobConfig job =
                (OpenClaw4jConfig.CronJobConfig) data.get("jobConfig");
            AgentManager agentManager = (AgentManager) data.get("agentManager");
            GatewaySessionRegistry sessionRegistry =
                (GatewaySessionRegistry) data.get("sessionRegistry");
            @SuppressWarnings("unchecked")
            Map<String, List<CronRun>> runHistory =
                (Map<String, List<CronRun>>) data.get("runHistory");
            @SuppressWarnings("unchecked")
            Map<String, CronRun> runningJobs =
                (Map<String, CronRun>) data.get("runningJobs");
            CronStateStore stateStore = (CronStateStore) data.get("stateStore");
            CronHistoryStore historyStore = (CronHistoryStore) data.get("historyStore");

            Logger log = LoggerFactory.getLogger(CronJobExecutor.class);
            log.info("Executing cron job: {} ({})", job.name, job.id);

            CronRun run = new CronRun(job.id);
            run.startedAt = System.currentTimeMillis();
            run.jobName = job.name;

            // 记录为正在运行
            if (runningJobs != null) {
                runningJobs.put(job.id, run);
            }

            // 更新调度状态
            if (stateStore != null) {
                stateStore.updateFire(job.id, run.startedAt);
            }

            String sessionKey = "cron:" + job.id;

            agentManager.runAsync(job.agentId, sessionKey, job.prompt, new AgentManager.AgentCallback() {
                @Override public void onChunk(String chunk) {}

                @Override
                public void onComplete(String fullResponse, ai.openclaw.agents.UsageStats usage) {
                    run.completedAt = System.currentTimeMillis();
                    run.status = "completed";
                    run.output = fullResponse.length() > 1000 ? fullResponse.substring(0, 1000) + "..." : fullResponse;

                    // 从运行列表移除
                    if (runningJobs != null) {
                        runningJobs.remove(job.id);
                    }

                    // 持久化历史
                    if (historyStore != null) {
                        historyStore.append(run);
                    }
                    // 更新内存缓存
                    addRunHistory(runHistory, job.id, run);

                    // 广播到UI客户端
                    sessionRegistry.broadcast("cron", Map.of(
                        "jobId", job.id,
                        "jobName", job.name,
                        "status", "completed",
                        "output", run.output
                    ));
                }

                @Override
                public void onError(String error) {
                    run.completedAt = System.currentTimeMillis();
                    run.status = "failed";
                    run.error = error;

                    // 从运行列表移除
                    if (runningJobs != null) {
                        runningJobs.remove(job.id);
                    }

                    if (historyStore != null) {
                        historyStore.append(run);
                    }
                    addRunHistory(runHistory, job.id, run);

                    sessionRegistry.broadcast("cron", Map.of(
                        "jobId", job.id,
                        "jobName", job.name,
                        "status", "failed",
                        "error", error
                    ));
                }
            });
        }
    }

    // ─── 执行记录 ────────────────────────────────────────────────────────────

    /**
     * 定时任务执行记录。
     * <p>
     * 记录单次任务执行的状态、时间和输出结果。
     * </p>
     */
    public static class CronRun {
        /** 任务ID */
        public String jobId;
        /** 任务名称 */
        public String jobName;
        /** 执行记录ID */
        public String id;
        /** 开始时间戳（毫秒） */
        public long startedAt;
        /** 完成时间戳（毫秒） */
        public long completedAt;
        /** 执行状态："running"、"completed"、"failed" */
        public String status = "running";
        /** 输出结果 */
        public String output;
        /** 错误信息 */
        public String error;

        /**
         * 构造执行记录实例。
         *
         * @param jobId 任务ID
         */
        public CronRun(String jobId) {
            this.jobId = jobId;
            this.id = UUID.randomUUID().toString();
        }
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────

    /**
     * 添加执行记录到内存历史，并保持最近50条记录。
     *
     * @param history 历史记录映射
     * @param jobId   任务ID
     * @param run     执行记录
     */
    private static void addRunHistory(Map<String, List<CronRun>> history, String jobId, CronRun run) {
        history.computeIfAbsent(jobId, k -> new ArrayList<>()).add(run);
        List<CronRun> runs = history.get(jobId);
        while (runs.size() > 50) runs.remove(0);
    }

    // ─── 调度状态存储 ────────────────────────────────────────────────────────────

    /**
     * 任务调度状态。
     */
    public static class JobState {
        /** 任务ID */
        public String jobId;
        /** 上次触发时间戳（毫秒） */
        public long lastFireTime;
        /** 下次触发时间戳（毫秒） */
        public long nextFireTime;
        /** 触发次数 */
        public int fireCount;

        public JobState() {}

        public JobState(String jobId) {
            this.jobId = jobId;
        }
    }

    /**
     * 调度状态存储。
     * <p>
     * 管理任务的触发状态，持久化到 state.json 文件。
     * </p>
     */
    public static class CronStateStore {
        private final Path stateFile;
        private final Map<String, JobState> states = new ConcurrentHashMap<>();

        /**
         * 构造状态存储实例。
         *
         * @param cronDir 定时任务数据目录
         */
        public CronStateStore(Path cronDir) {
            this.stateFile = cronDir.resolve("state.json");
            try {
                Files.createDirectories(cronDir);
            } catch (IOException e) {
                log.warn("无法创建cron目录: {}", e.getMessage());
            }
        }

        /**
         * 从磁盘加载状态。
         */
        @SuppressWarnings("unchecked")
        public void load() {
            if (!Files.exists(stateFile)) return;
            try {
                Map<String, Object> data = mapper.readValue(stateFile.toFile(), Map.class);
                Map<String, Object> jobs = (Map<String, Object>) data.get("jobs");
                if (jobs != null) {
                    for (Map.Entry<String, Object> entry : jobs.entrySet()) {
                        Map<String, Object> jobData = (Map<String, Object>) entry.getValue();
                        JobState state = new JobState(entry.getKey());
                        state.lastFireTime = ((Number) jobData.getOrDefault("lastFireTime", 0L)).longValue();
                        state.nextFireTime = ((Number) jobData.getOrDefault("nextFireTime", 0L)).longValue();
                        state.fireCount = ((Number) jobData.getOrDefault("fireCount", 0)).intValue();
                        states.put(entry.getKey(), state);
                    }
                }
                log.info("Loaded {} job states from {}", states.size(), stateFile);
            } catch (Exception e) {
                log.warn("加载调度状态失败: {}", e.getMessage());
            }
        }

        /**
         * 保存状态到磁盘。
         */
        public void save() {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                Map<String, Object> jobs = new LinkedHashMap<>();
                for (Map.Entry<String, JobState> entry : states.entrySet()) {
                    Map<String, Object> jobData = new LinkedHashMap<>();
                    jobData.put("lastFireTime", entry.getValue().lastFireTime);
                    jobData.put("nextFireTime", entry.getValue().nextFireTime);
                    jobData.put("fireCount", entry.getValue().fireCount);
                    jobs.put(entry.getKey(), jobData);
                }
                data.put("jobs", jobs);
                mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), data);
            } catch (Exception e) {
                log.warn("保存调度状态失败: {}", e.getMessage());
            }
        }

        /**
         * 获取任务状态。
         *
         * @param jobId 任务ID
         * @return 任务状态，不存在时返回null
         */
        public JobState get(String jobId) {
            return states.get(jobId);
        }

        /**
         * 更新任务触发时间。
         *
         * @param jobId    任务ID
         * @param fireTime 触发时间戳
         */
        public void updateFire(String jobId, long fireTime) {
            JobState state = states.computeIfAbsent(jobId, JobState::new);
            state.lastFireTime = fireTime;
            state.fireCount++;
            save();
        }
    }

    // ─── 执行历史存储 ────────────────────────────────────────────────────────────

    /**
     * 执行历史存储。
     * <p>
     * 管理任务执行记录，持久化到 JSONL 文件。
     * 每个任务一个文件，格式为每行一个 JSON 对象。
     * </p>
     */
    public static class CronHistoryStore {
        private final Path historyDir;

        /**
         * 构造历史存储实例。
         *
         * @param cronDir 定时任务数据目录
         */
        public CronHistoryStore(Path cronDir) {
            this.historyDir = cronDir.resolve("history");
            try {
                Files.createDirectories(historyDir);
            } catch (IOException e) {
                log.warn("无法创建历史目录: {}", e.getMessage());
            }
        }

        /**
         * 追加执行记录到历史文件。
         *
         * @param run 执行记录
         */
        public void append(CronRun run) {
            Path file = getHistoryFile(run.jobId);
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                writer.write(mapper.writeValueAsString(run));
                writer.newLine();
            } catch (Exception e) {
                log.warn("追加执行历史失败: {}", e.getMessage());
            }
        }

        /**
         * 加载任务的执行历史。
         *
         * @param jobId 任务ID
         * @param limit 最大条数
         * @return 执行记录列表（最新的在前）
         */
        public List<CronRun> load(String jobId, int limit) {
            Path file = getHistoryFile(jobId);
            if (!Files.exists(file)) return new ArrayList<>();

            List<CronRun> runs = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        CronRun run = mapper.readValue(line, CronRun.class);
                        runs.add(run);
                    }
                }
            } catch (Exception e) {
                log.warn("加载执行历史失败: {}", e.getMessage());
            }

            // 返回最近的N条（从后往前取）
            if (runs.size() > limit) {
                return runs.subList(runs.size() - limit, runs.size());
            }
            return runs;
        }

        /**
         * 获取历史文件路径。
         *
         * @param jobId 任务ID
         * @return 历史文件路径
         */
        private Path getHistoryFile(String jobId) {
            // 将jobId转换为安全文件名
            String safeName = jobId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            return historyDir.resolve(safeName + ".jsonl");
        }
    }
}
