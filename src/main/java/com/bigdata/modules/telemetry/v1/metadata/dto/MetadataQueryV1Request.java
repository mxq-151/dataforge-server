package com.bigdata.modules.telemetry.v1.metadata.dto;

import com.bigdata.modules.telemetry.v1.metadata.model.MetadataTarget;

import java.util.List;

/**
 * 服务版本与配置查询请求。
 *
 * <p>{@code targets} 必填，1..{@code max-targets}；{@code scope} 取 version / config / both / metrics，
 * 缺省 both；{@code sections} / {@code keys} 为空表示不过滤；{@code limit} 是单个 target 的
 * 配置条数或指标名条数上限。</p>
 */
public class MetadataQueryV1Request {

    private List<MetadataTarget> targets;
    private String scope;
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
