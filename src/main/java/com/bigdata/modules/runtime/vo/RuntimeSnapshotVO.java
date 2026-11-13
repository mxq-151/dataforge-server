package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单作业运行时快照：一次调用返回状态、组件（TM / executor / vertex / stage）、
 * 指定指标族与 checkpoint 摘要，避免 N 次往返。
 */
public class RuntimeSnapshotVO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private String mode;
    private String jobId;
    private String jobName;
    private String state;
    private Long startTime;
    private Long endTime;
    private Long durationMs;
    private Long capturedAt;

    private boolean degraded;
    private String degradationReason;
    private String errorCode;

    private List<RuntimeComponentVO> components = new ArrayList<>();
    private List<RuntimeMetricVO> jobMetrics = new ArrayList<>();
    private RuntimeCheckpointVO checkpoint;
    /** 已请求但当前服务/版本不提供的指标族 -> 原因 */
    private Map<String, String> unavailableFamilies = new LinkedHashMap<>();
    /** includeConfig=true 时的作业配置（已脱敏） */
    private Map<String, Object> jobConfig;
    private List<String> warnings = new ArrayList<>();

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public String getDegradationReason() {
        return degradationReason;
    }

    public void setDegradationReason(String degradationReason) {
        this.degradationReason = degradationReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public List<RuntimeComponentVO> getComponents() {
        return components;
    }

    public void setComponents(List<RuntimeComponentVO> components) {
        this.components = components == null ? new ArrayList<>() : components;
    }

    public List<RuntimeMetricVO> getJobMetrics() {
        return jobMetrics;
    }

    public void setJobMetrics(List<RuntimeMetricVO> jobMetrics) {
        this.jobMetrics = jobMetrics == null ? new ArrayList<>() : jobMetrics;
    }

    public RuntimeCheckpointVO getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(RuntimeCheckpointVO checkpoint) {
        this.checkpoint = checkpoint;
    }

    public Map<String, String> getUnavailableFamilies() {
        return unavailableFamilies;
    }

    public void setUnavailableFamilies(Map<String, String> unavailableFamilies) {
        this.unavailableFamilies = unavailableFamilies == null
                ? new LinkedHashMap<>() : unavailableFamilies;
    }

    public Map<String, Object> getJobConfig() {
        return jobConfig;
    }

    public void setJobConfig(Map<String, Object> jobConfig) {
        this.jobConfig = jobConfig;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }
}
