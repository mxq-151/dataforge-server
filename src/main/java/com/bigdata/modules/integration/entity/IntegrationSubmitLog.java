package com.bigdata.modules.integration.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * 数据集成提交 / 下线操作记录（含参数快照与集群返回日志）。
 */
@TableName("integration_submit_log")
public class IntegrationSubmitLog extends BaseEntity {

    private Long taskId;
    /** SUBMIT / OFFLINE / STATUS */
    private String action;
    private String clusterType;
    private String jobId;
    /** SUCCESS / FAILED */
    private String resultStatus;
    /** 本次提交生效的完整参数快照（JSON） */
    private String paramsSnapshot;
    /** 提交时生成的作业配置或集群返回内容 */
    private String logContent;
    private String errorMessage;
    private Long operatorId;
    private String operatorName;
    private Long durationMs;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getClusterType() {
        return clusterType;
    }

    public void setClusterType(String clusterType) {
        this.clusterType = clusterType;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(String resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getParamsSnapshot() {
        return paramsSnapshot;
    }

    public void setParamsSnapshot(String paramsSnapshot) {
        this.paramsSnapshot = paramsSnapshot;
    }

    public String getLogContent() {
        return logContent;
    }

    public void setLogContent(String logContent) {
        this.logContent = logContent;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
