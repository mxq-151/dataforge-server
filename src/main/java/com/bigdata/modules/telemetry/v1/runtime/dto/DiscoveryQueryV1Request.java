package com.bigdata.modules.telemetry.v1.runtime.dto;

import com.bigdata.modules.telemetry.v1.model.Target;

import java.util.ArrayList;
import java.util.List;

/** discovery 请求：sources / jobs / components 三类作用域。 */
public class DiscoveryQueryV1Request {

    /** sources / jobs / components；必填。 */
    private String scope;
    private Target target;
    /** live / history；缺省时用配置实例的 mode。 */
    private String sourceMode;
    private String jobId;
    /** taskmanager / vertex（Flink）、executor / stage（Spark）。 */
    private String componentRole;
    /** 只返回指定组件；空表示全部。 */
    private List<String> components = new ArrayList<>();
    private Integer limit;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

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

    public String getComponentRole() {
        return componentRole;
    }

    public void setComponentRole(String componentRole) {
        this.componentRole = componentRole;
    }

    public List<String> getComponents() {
        return components;
    }

    public void setComponents(List<String> components) {
        this.components = components == null ? new ArrayList<>() : components;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
