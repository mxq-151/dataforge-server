package com.bigdata.modules.telemetry.dto;


import java.util.List;

/**
 * 服务版本与配置查询请求。
 *
 * <p>{@code scope=version/config/both/metrics} 时 {@code targets} 必填，1..{@code max-targets}；
 * {@code scope=targets} 时 {@code targets} 可空，可作为 serviceType / componentId 过滤条件。
 * {@code taskName} 只用于 targets 发现，精确优先、必要时模糊；{@code matchMode} 取
 * {@code auto / exact / fuzzy}，缺省 auto。{@code sections} / {@code keys} 为空表示不过滤；
 * {@code limit} 是单个 target 的配置条数、指标名条数或目标条数上限。</p>
 */
public class MetadataQueryRequest {

    private List<MetadataTarget> targets;
    private String scope;
    private String taskName;
    private String matchMode;
    private List<String> sections;
    private List<String> keys;
    private Integer limit;

    public List<MetadataTarget> getTargets() {
        return targets;
    }

    public void setTargets(List<MetadataTarget> targets) {
        this.targets = targets;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(String matchMode) {
        this.matchMode = matchMode;
    }

    public List<String> getSections() {
        return sections;
    }

    public void setSections(List<String> sections) {
        this.sections = sections;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
