package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

import java.time.LocalDate;

/**
 * scheduler_task_run_daily —— 调度任务运行日汇总（T+1，由 DolphinScheduler 采集）。
 *
 * <p>粒度：统计日 × 项目 × 工作流 × 节点 × 任务类型 一行。</p>
 *
 * <h3>为什么是日聚合而不是运行明细</h3>
 * <p>看板只需要「窗口内累计时长 / 执行次数 / 延迟数」这类可加指标，
 * 日聚合后表体量与「表数量」同量级，查询不必扫明细。明细仍留在 DolphinScheduler 侧，
 * 需要回溯时去调度系统查，不在这里存第二份副本的最大集。</p>
 *
 * <h3>资源指标为什么不在表里</h3>
 * <p>CPU 核·小时、内存 GB·小时只有 YARN 能提供（由 allocatedMB / allocatedVCores 与
 * 运行时长换算）。本平台部署中 Spark 以 local 模式运行、没有 YARN，
 * 因此这两项没有数据源，不落库也不伪造。</p>
 *
 * @author dataforge
 */
@TableName("scheduler_task_run_daily")
public class SchedulerTaskRunDaily extends BaseEntity {

    /** 统计日（取任务实际开始日） */
    private LocalDate statDate;

    /** 调度项目编码 */
    private String projectCode;

    /** 调度项目名 */
    private String projectName;

    /** 工作流编码 */
    private Long workflowCode;

    /** 工作流名 */
    private String workflowName;

    /** 节点（任务）名 */
    private String nodeName;

    /** 任务类型（SPARK / HIVE / FLINK 等） */
    private String taskType;

    /** 任务实例数（窗口内执行次数） */
    private Integer taskRunCount;

    /** 延迟实例数：实际开始晚于计划开始 */
    private Integer delayedCount;

    /** 成功实例数 */
    private Integer successCount;

    /** 失败实例数 */
    private Integer failedCount;

    /** 累计运行时长（秒） */
    private Long totalDurationSeconds;

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Long getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(Long workflowCode) { this.workflowCode = workflowCode; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Integer getTaskRunCount() { return taskRunCount; }
    public void setTaskRunCount(Integer taskRunCount) { this.taskRunCount = taskRunCount; }
    public Integer getDelayedCount() { return delayedCount; }
    public void setDelayedCount(Integer delayedCount) { this.delayedCount = delayedCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Long getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(Long totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }
}
