package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配置参数查询结果：作业配置 + 集群配置 + executor/TM 的 JVM 与资源配置（已脱敏） */
public class RuntimeConfigVO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private String mode;
    private String jobId;
    private Long capturedAt;
    private boolean available;
    private String reason;
    /** 分区名称 -> 配置键值，例如 job / jobmanager / sparkProperties / systemProperties */
    private Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    /** executor / taskmanager 资源摘要（不含全量指标） */
    private List<RuntimeComponentVO> components = new ArrayList<>();
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

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Map<String, String>> getSections() {
        return sections;
    }

    public void setSections(Map<String, Map<String, String>> sections) {
        this.sections = sections == null ? new LinkedHashMap<>() : sections;
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
}
