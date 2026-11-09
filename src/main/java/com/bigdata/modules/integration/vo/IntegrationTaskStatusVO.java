package com.bigdata.modules.integration.vo;

import java.time.LocalDateTime;

/**
 * 任务运行状态查询结果。
 *
 * <p>集群不可达时不抛异常，而是返回 clusterReachable=false + message，
 * 让前端能明确展示「最后一次已知状态 + 集群连接失败原因」，而不是静默当成正常。</p>
 */
public class IntegrationTaskStatusVO {

    private Long taskId;
    private String status;
    private String statusLabel;
    private String jobId;
    /** 集群侧原始状态，如 RUNNING / FINISHED / FAILED */
    private String clusterStatus;
    private boolean clusterReachable;
    private String message;
    private LocalDateTime checkedAt;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getClusterStatus() {
        return clusterStatus;
    }

    public void setClusterStatus(String clusterStatus) {
        this.clusterStatus = clusterStatus;
    }

    public boolean isClusterReachable() {
        return clusterReachable;
    }

    public void setClusterReachable(boolean clusterReachable) {
        this.clusterReachable = clusterReachable;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }
}
