package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * TOP 任务清单项（消耗计算资源 / 运行时长）。
 *
 * <p>字段名与前端 {@code AssetTopComputeTaskVO} 逐字对应。</p>
 *
 * <h3>资源指标为什么可能为 null</h3>
 * <p>{@code cpuHours} 与 {@code memoryGbHours} 只有 YARN 能提供。本平台 Spark 以 local
 * 模式运行、没有 YARN，因此这两项恒为 {@code null}（前端渲染为「—」），
 * 排序退化为按 {@code runDurationSeconds} 降序。刻意不填 0 —— 0 会被读成「不消耗资源」。</p>
 *
 * @author dataforge
 */
public class AssetTopComputeTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String workflowName;
    private String nodeName;

    /** 任务类型（SPARK / HIVE / FLINK…） */
    private String taskType;

    /** 调度项目名 */
    private String projectName;

    /** 消耗 CPU 核·小时；无 YARN 数据源时为 null */
    private Double cpuHours;

    /** 内存 GB·小时；无 YARN 数据源时为 null */
    private Double memoryGbHours;

    /** 统计窗口内累计运行时长（秒） */
    private Long runDurationSeconds;

    /** 统计窗口内执行次数 */
    private Integer execCount;

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Double getCpuHours() { return cpuHours; }
    public void setCpuHours(Double cpuHours) { this.cpuHours = cpuHours; }
    public Double getMemoryGbHours() { return memoryGbHours; }
    public void setMemoryGbHours(Double memoryGbHours) { this.memoryGbHours = memoryGbHours; }
    public Long getRunDurationSeconds() { return runDurationSeconds; }
    public void setRunDurationSeconds(Long runDurationSeconds) { this.runDurationSeconds = runDurationSeconds; }
    public Integer getExecCount() { return execCount; }
    public void setExecCount(Integer execCount) { this.execCount = execCount; }
}
