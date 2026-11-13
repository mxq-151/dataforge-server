package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.List;

/** 组件列表结果（不含或只含基础指标） */
public class RuntimeComponentsVO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private String mode;
    private String jobId;
    private List<RuntimeComponentVO> components = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private boolean degraded;
    private String degradationReason;
    private Long capturedAt;

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

    public List<RuntimeComponentVO> getComponents() {
        return components;
    }

    public void setComponents(List<RuntimeComponentVO> components) {
        this.components = components == null ? new ArrayList<>() : components;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
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

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }
}
