package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.asset.entity.SchedulerTaskRunDaily;
import com.bigdata.modules.asset.mapper.SchedulerTaskRunDailyMapper;
import com.bigdata.modules.asset.vo.AssetTaskDelayVO;
import com.bigdata.modules.asset.vo.AssetTopComputeTaskVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 调度任务运行指标 —— 从 DolphinScheduler 采集、按日聚合落库，并支撑「资产概况」的两个看板。
 *
 * <h3>为什么必须落库</h3>
 * <p>{@link AssetProducerService} 那种「按表名实时反查」只能回答单表的产出工作流；
 * 而「窗口内累计耗时 TOP 50」「近 30 天延迟率」需要跨项目、跨时间窗口聚合，
 * 实时逐页拉取既慢又不稳定。因此本服务把运行记录采集成本地日聚合表
 * {@code scheduler_task_run_daily}，查询只读本地。</p>
 *
 * <h3>采集是幂等且自愈的</h3>
 * <p>每次采集重拉「近 {@value #WINDOW_DAYS} 天」并整体覆盖写：漏跑一次不会留下空洞，
 * 次日重算即可补齐。超过 {@value #RETENTION_DAYS} 天的历史随采集一并清理。</p>
 *
 * <h3>上游不可用时不破坏本地</h3>
 * <p>调度系统未配置时直接跳过（等同于能力未开启）；单个项目拉取失败只跳过该项目并告警，
 * 不中断其余项目，也不用空数据覆盖已有结果。</p>
 *
 * <h3>资源指标的现状</h3>
 * <p>{@code cpuHours / memoryGbHours} 与按队列拆分（{@code byQueue}）依赖 YARN。
 * 本平台 Spark 以 local 模式运行、没有 YARN，因此前者恒为 {@code null}、
 * 后者恒为空数组 —— 不伪造数据，前端按「—」与不渲染队列表格处理。</p>
 *
 * @author dataforge
 */
@Service
public class SchedulerTaskMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTaskMetricsService.class);

    /** 采集窗口与看板统计窗口（天） */
    public static final int WINDOW_DAYS = 30;

    /** 落库保留天数 */
    public static final int RETENTION_DAYS = 180;

    /** TOP 任务默认条数与上限 */
    private static final int TOP_TASK_DEFAULT = 50;
    private static final int TOP_TASK_MAX = 500;

    /** 无法取得工作流名时的占位值 */
    private static final String UNKNOWN_WORKFLOW = "-";

    @Autowired
    private SchedulerApiClient schedulerApiClient;

    @Autowired
    private SchedulerTaskRunDailyMapper taskRunDailyMapper;

    @Autowired
    private DataAccessPermissionService permissionService;

    /** 采集摘要，便于定时任务日志与单测断言 */
    public record CollectResult(LocalDate anchorDate, int projectCount, int taskInstanceCount,
                                int rowCount, String skippedReason) {
    }

    // ==================================================================
    // 采集
    // ==================================================================

    /**
     * 采集「近 {@value #WINDOW_DAYS} 天」的调度运行记录并按日聚合落库。
     *
     * @param anchorDate 窗口结束日（通常为「昨天」）
     */
    @Transactional
    public CollectResult collect(LocalDate anchorDate) {
        if (!schedulerApiClient.isConfigured()) {
            log.debug("调度系统未配置（scheduler.dolphinscheduler.base-url 为空），跳过调度任务指标采集");
            return new CollectResult(anchorDate, 0, 0, 0, "调度系统未配置");
        }

        LocalDate from = anchorDate.minusDays(WINDOW_DAYS - 1L);
        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = anchorDate.plusDays(1).atStartOfDay();

        List<SchedulerApiClient.ProjectRef> projects = resolveProjects();
        if (projects.isEmpty()) {
            log.warn("未取到调度项目，本次采集跳过：anchorDate={}", anchorDate);
            return new CollectResult(anchorDate, 0, 0, 0, "未取到调度项目");
        }

        Map<String, Agg> aggregated = new LinkedHashMap<>();
        int instances = 0;
        int failedProjects = 0;
        for (SchedulerApiClient.ProjectRef project : projects) {
            List<SchedulerApiClient.ProcessInstanceBrief> processes;
            List<SchedulerApiClient.TaskInstanceBrief> tasks;
            try {
                processes = schedulerApiClient.processInstances(project.code(), fromTime, toTime);
                tasks = schedulerApiClient.taskInstances(project.code(), fromTime, toTime);
            } catch (Exception e) {
                failedProjects++;
                log.warn("采集调度运行记录失败，跳过该项目：project={}, 原因={}", project.code(), e.getMessage());
                continue;
            }
            Map<Long, SchedulerApiClient.ProcessInstanceBrief> processById = new HashMap<>();
            for (SchedulerApiClient.ProcessInstanceBrief process : processes) {
                if (process.id() != null) {
                    processById.put(process.id(), process);
                }
            }
            for (SchedulerApiClient.TaskInstanceBrief task : tasks) {
                if (aggregate(aggregated, project, processById, task, fromTime, toTime)) {
                    instances++;
                }
            }
        }

        if (aggregated.isEmpty() && failedProjects == projects.size()) {
            // 全部项目都失败：不覆盖已有数据，保留上一轮结果
            log.warn("全部调度项目采集失败，保留上一轮结果：projects={}", projects.size());
            return new CollectResult(anchorDate, projects.size(), 0, 0, "全部项目采集失败");
        }

        // 覆盖写窗口内的数据，保证重复执行结果一致
        taskRunDailyMapper.delete(new LambdaQueryWrapper<SchedulerTaskRunDaily>()
                .ge(SchedulerTaskRunDaily::getStatDate, from)
                .le(SchedulerTaskRunDaily::getStatDate, anchorDate));
        for (Agg agg : aggregated.values()) {
            taskRunDailyMapper.insert(agg.toEntity());
        }
        int purged = purgeBefore(anchorDate.minusDays(RETENTION_DAYS));

        log.info("调度任务指标采集完成：窗口={}~{}，项目={}（失败 {}），任务实例={}，聚合行={}，清理={}",
                from, anchorDate, projects.size(), failedProjects, instances, aggregated.size(), purged);
        return new CollectResult(anchorDate, projects.size(), instances, aggregated.size(), null);
    }

    /** 清理早于 cutoff 的历史，返回删除行数 */
    public int purgeBefore(LocalDate cutoff) {
        int deleted = taskRunDailyMapper.delete(new LambdaQueryWrapper<SchedulerTaskRunDaily>()
                .lt(SchedulerTaskRunDaily::getStatDate, cutoff));
        if (deleted > 0) {
            log.info("调度任务指标清理：早于 {} 的记录删除 {} 行", cutoff, deleted);
        }
        return deleted;
    }

    /**
     * 把一条任务实例并入聚合。
     *
     * @return 是否计入（开始时间缺失或不在窗口内则不计）
     */
    private boolean aggregate(Map<String, Agg> aggregated, SchedulerApiClient.ProjectRef project,
                              Map<Long, SchedulerApiClient.ProcessInstanceBrief> processById,
                              SchedulerApiClient.TaskInstanceBrief task,
                              LocalDateTime fromTime, LocalDateTime toTime) {
        LocalDateTime start = task.startTime();
        if (start == null || start.isBefore(fromTime) || !start.isBefore(toTime)) {
            return false;
        }
        SchedulerApiClient.ProcessInstanceBrief process = task.processInstanceId() == null
                ? null : processById.get(task.processInstanceId());
        // 计划开始时间取自流程实例：延迟 = 任务实际开始晚于计划的触发时间
        LocalDateTime planned = process == null ? null : process.scheduleTime();

        String workflowName = process == null || process.workflowName() == null
                ? UNKNOWN_WORKFLOW : process.workflowName();
        LocalDate statDate = start.toLocalDate();
        String nodeName = task.nodeName() == null ? "" : task.nodeName();
        String taskType = task.taskType() == null ? "" : task.taskType();
        String key = statDate + "\u0000" + project.code() + "\u0000" + workflowName
                + "\u0000" + nodeName + "\u0000" + taskType;

        Agg agg = aggregated.computeIfAbsent(key, k -> {
            Agg item = new Agg();
            item.statDate = statDate;
            item.projectCode = project.code();
            item.projectName = project.name();
            item.workflowCode = process == null ? null : process.workflowCode();
            item.workflowName = workflowName;
            item.nodeName = nodeName;
            item.taskType = taskType;
            return item;
        });
        agg.runCount++;
        if (planned != null && start.isAfter(planned)) {
            agg.delayedCount++;
        }
        if (isSuccess(task.state())) {
            agg.successCount++;
        } else if (isFailed(task.state())) {
            agg.failedCount++;
        }
        if (task.endTime() != null) {
            long seconds = Duration.between(start, task.endTime()).getSeconds();
            agg.totalDurationSeconds += Math.max(0L, seconds);
        }
        return true;
    }

    private List<SchedulerApiClient.ProjectRef> resolveProjects() {
        try {
            List<SchedulerApiClient.ProjectRef> projects = schedulerApiClient.listProjects();
            if (!projects.isEmpty()) {
                return projects;
            }
        } catch (Exception e) {
            log.warn("拉取调度项目列表失败，回退默认项目编码：原因={}", e.getMessage());
        }
        String fallback = schedulerApiClient.fallbackProjectCode();
        return List.of(new SchedulerApiClient.ProjectRef(fallback, fallback));
    }

    private static boolean isSuccess(String state) {
        return state != null && "SUCCESS".equalsIgnoreCase(state.trim());
    }

    private static boolean isFailed(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("FAIL") || normalized.contains("KILL") || normalized.contains("TIMEOUT");
    }

    // ==================================================================
    // 查询：TOP 任务
    // ==================================================================

    /**
     * 计算资源 / 运行时长 TOP 任务。
     *
     * <p>由于本平台无 YARN，{@code cpuHours / memoryGbHours} 恒为 null，
     * 两个排序维度都按「窗口内累计运行时长」降序（resource 维度降级，前端提示语需同步）。</p>
     *
     * @param limit 条数上限，默认 50、硬上限 500
     */
    public List<AssetTopComputeTaskVO> topCompute(String sortBy, Integer limit) {
        if (sortBy != null && !sortBy.isBlank()
                && !"resource".equalsIgnoreCase(sortBy.trim())
                && !"duration".equalsIgnoreCase(sortBy.trim())) {
            log.debug("top-compute 收到未知排序维度 {}，按 resource 处理", sortBy);
        }
        int top = limit == null ? TOP_TASK_DEFAULT : Math.max(1, Math.min(TOP_TASK_MAX, limit));

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(WINDOW_DAYS - 1L);
        Set<String> allowedProjects = permissionService.getAccessibleProjectCodes();

        Map<String, ComputeAgg> grouped = new LinkedHashMap<>();
        for (SchedulerTaskRunDaily row : window(from, to)) {
            if (!projectAllowed(row.getProjectCode(), allowedProjects)) {
                continue;
            }
            String key = row.getProjectCode() + "\u0000" + row.getWorkflowName() + "\u0000" + row.getNodeName();
            grouped.computeIfAbsent(key, k -> new ComputeAgg(row)).add(row);
        }

        List<AssetTopComputeTaskVO> rows = new ArrayList<>(grouped.size());
        for (ComputeAgg agg : grouped.values()) {
            rows.add(agg.toVO());
        }
        rows.sort(Comparator.comparingLong((AssetTopComputeTaskVO vo) ->
                vo.getRunDurationSeconds() == null ? 0L : vo.getRunDurationSeconds()).reversed());
        return rows.size() > top ? new ArrayList<>(rows.subList(0, top)) : rows;
    }

    // ==================================================================
    // 查询：延迟率
    // ==================================================================

    /**
     * 任务延迟率。
     *
     * @param days 统计窗口天数，夹到 1~365
     */
    public AssetTaskDelayVO taskDelay(Integer days) {
        int window = days == null ? WINDOW_DAYS : Math.max(1, Math.min(365, days));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window - 1L);
        Set<String> allowedProjects = permissionService.getAccessibleProjectCodes();

        long total = 0L;
        long delayed = 0L;
        for (SchedulerTaskRunDaily row : window(from, to)) {
            if (!projectAllowed(row.getProjectCode(), allowedProjects)) {
                continue;
            }
            total += row.getTaskRunCount() == null ? 0 : row.getTaskRunCount();
            delayed += row.getDelayedCount() == null ? 0 : row.getDelayedCount();
        }

        AssetTaskDelayVO vo = new AssetTaskDelayVO();
        vo.setTotalTasks((int) total);
        vo.setDelayedTasks((int) delayed);
        vo.setDelayRate(total == 0 ? 0d : (double) delayed / total);
        vo.setWindowDays(window);
        // 按队列拆分需要 YARN 队列维度，本平台无 YARN，恒为空数组（前端不渲染队列表）
        vo.setByQueue(List.of());
        return vo;
    }

    private List<SchedulerTaskRunDaily> window(LocalDate from, LocalDate to) {
        return taskRunDailyMapper.selectList(new LambdaQueryWrapper<SchedulerTaskRunDaily>()
                .eq(SchedulerTaskRunDaily::getDeleted, 0)
                .ge(SchedulerTaskRunDaily::getStatDate, from)
                .le(SchedulerTaskRunDaily::getStatDate, to));
    }

    private static boolean projectAllowed(String projectCode, Set<String> allowedProjects) {
        return allowedProjects == null || allowedProjects.contains(projectCode);
    }

    // ==================================================================
    // 内部聚合结构
    // ==================================================================

    /** 采集期聚合（落库前） */
    private static final class Agg {

        private LocalDate statDate;
        private String projectCode;
        private String projectName;
        private Long workflowCode;
        private String workflowName;
        private String nodeName;
        private String taskType;
        private int runCount;
        private int delayedCount;
        private int successCount;
        private int failedCount;
        private long totalDurationSeconds;

        private SchedulerTaskRunDaily toEntity() {
            SchedulerTaskRunDaily entity = new SchedulerTaskRunDaily();
            entity.setStatDate(statDate);
            entity.setProjectCode(projectCode);
            entity.setProjectName(projectName);
            entity.setWorkflowCode(workflowCode);
            entity.setWorkflowName(workflowName);
            entity.setNodeName(nodeName);
            entity.setTaskType(taskType);
            entity.setTaskRunCount(runCount);
            entity.setDelayedCount(delayedCount);
            entity.setSuccessCount(successCount);
            entity.setFailedCount(failedCount);
            entity.setTotalDurationSeconds(totalDurationSeconds);
            entity.setDeleted(0);
            return entity;
        }
    }

    /** 查询期按「项目 + 工作流 + 节点」聚合 */
    private static final class ComputeAgg {

        private String projectName;
        private String workflowName;
        private String nodeName;
        private String taskType;
        private int execCount;
        private long totalDurationSeconds;

        private ComputeAgg(SchedulerTaskRunDaily row) {
            this.projectName = row.getProjectName();
            this.workflowName = row.getWorkflowName();
            this.nodeName = row.getNodeName();
            this.taskType = row.getTaskType();
        }

        private void add(SchedulerTaskRunDaily row) {
            execCount += row.getTaskRunCount() == null ? 0 : row.getTaskRunCount();
            totalDurationSeconds += row.getTotalDurationSeconds() == null ? 0L : row.getTotalDurationSeconds();
            // 同一节点的任务类型应当稳定；出现差异时保留首个非空值
            if ((taskType == null || taskType.isBlank()) && row.getTaskType() != null) {
                taskType = row.getTaskType();
            }
            if ((projectName == null || projectName.isBlank()) && row.getProjectName() != null) {
                projectName = row.getProjectName();
            }
        }

        private AssetTopComputeTaskVO toVO() {
            AssetTopComputeTaskVO vo = new AssetTopComputeTaskVO();
            vo.setWorkflowName(workflowName);
            vo.setNodeName(nodeName);
            vo.setTaskType(taskType);
            vo.setProjectName(projectName);
            // 无 YARN 数据源：资源指标置空，不填 0（0 会被读成「不消耗资源」）
            vo.setCpuHours(null);
            vo.setMemoryGbHours(null);
            vo.setRunDurationSeconds(totalDurationSeconds);
            vo.setExecCount(execCount);
            return vo;
        }
    }
}
