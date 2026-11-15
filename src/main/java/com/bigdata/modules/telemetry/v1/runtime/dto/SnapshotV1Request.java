package com.bigdata.modules.telemetry.v1.runtime.dto;

import com.bigdata.modules.telemetry.v1.model.Target;

import java.util.ArrayList;
import java.util.List;

/** 单作业快照请求。 */
public class SnapshotV1Request {

    private Target target;
    private String sourceMode;
    /** Flink jobId / Spark applicationId；必填。 */
    private String jobId;
    /** checkpoint / throughput / jvm_gc / backpressure / shuffle / failures；空取默认。 */
    private List<String> families = new ArrayList<>();
    /** 显式原始指标名（Flink Status.* 或 Spark 字段名）。 */
    private List<String> metrics = new ArrayList<>();
    /** 只返回指定组件；空表示全部。 */
    private List<String> components = new ArrayList<>();
    private Boolean includeConfig;

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
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

    public Boolean getIncludeConfig() {
        return includeConfig;
    }

    public void setIncludeConfig(Boolean includeConfig) {
        this.includeConfig = includeConfig;
    }
}
