package com.bigdata.modules.runtime.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时指标查询参数。
 *
 * <p>定位方式二选一：{@code sourceId}，或 {@code serviceType + cluster + mode}。
 * {@code mode} 用于区分 Spark 的 history（History Server）与 live（运行中 UI）。</p>
 */
public class RuntimeQueryDTO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    /** live / history */
    private String mode;
    private String jobId;
    /** 指标族：jvm_gc / checkpoint / throughput / backpressure / shuffle / failures */
    private List<String> families = new ArrayList<>();
    /** 显式原始指标名（覆盖白名单） */
    private List<String> metrics = new ArrayList<>();
    /** 只返回指定组件（TM id / executor id / vertex / stage）；空表示全部 */
    private List<String> components = new ArrayList<>();
    /** 可选：executor / task_manager 等角色约束 */
    private String componentRole;
    private Boolean includeConfig;
    private Boolean includeComponents;
    private Boolean includeVertices;
    /** 对比窗口（秒），默认 60 */
    private Long windowSeconds;

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

    public List<String> getFamilies() {
        return families;
    }

    public void setFamilies(List<String> families) {
        this.families = families == null ? new ArrayList<>() : families;
    }

    public List<String> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<String> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : metrics;
    }

    public List<String> getComponents() {
        return components;
    }

    public void setComponents(List<String> components) {
        this.components = components == null ? new ArrayList<>() : components;
    }

    public String getComponentRole() {
        return componentRole;
    }

    public void setComponentRole(String componentRole) {
        this.componentRole = componentRole;
    }

    public Boolean getIncludeConfig() {
        return includeConfig;
    }

    public void setIncludeConfig(Boolean includeConfig) {
        this.includeConfig = includeConfig;
    }

    public Boolean getIncludeComponents() {
        return includeComponents;
    }

    public void setIncludeComponents(Boolean includeComponents) {
        this.includeComponents = includeComponents;
    }

    public Boolean getIncludeVertices() {
        return includeVertices;
    }

    public void setIncludeVertices(Boolean includeVertices) {
        this.includeVertices = includeVertices;
    }

    public Long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
