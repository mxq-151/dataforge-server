package com.bigdata.modules.telemetry.v1.runtime.dto;

import com.bigdata.modules.telemetry.v1.model.Target;

import java.util.ArrayList;
import java.util.List;

/** 作业/集群配置参数请求；返回值已脱敏。 */
public class ConfigV1Request {

    private Target target;
    private String sourceMode;
    private String jobId;
    private List<String> components = new ArrayList<>();

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

    public List<String> getComponents() {
        return components;
    }

    public void setComponents(List<String> components) {
        this.components = components == null ? new ArrayList<>() : components;
    }
}
