package com.bigdata.modules.telemetry.dto;

import com.bigdata.modules.telemetry.common.model.Target;

import java.util.Map;

/**
 * 引擎异常摘要查询请求。
 *
 * <p>{@code ref.jobId} 必填；{@code ref.exceptionName} 或 {@code ref.exceptionIndex} 用于单条详情。
 * 条数、并发异常数与 stacktrace 长度都可覆盖，但最终仍会被后端配置上限夹住。</p>
 */
public class ExceptionQueryRequest {

    private Target target;
    private Map<String, String> ref;
    private Integer maxExceptions;
    private Integer maxConcurrentExceptions;
    private Boolean includeStackTraces;
    private Long timeoutMs;
    private Boolean includeBackendCandidates;

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public Map<String, String> getRef() {
        return ref;
    }

    public void setRef(Map<String, String> ref) {
        this.ref = ref;
    }

    public Integer getMaxExceptions() {
        return maxExceptions;
    }

    public void setMaxExceptions(Integer maxExceptions) {
        this.maxExceptions = maxExceptions;
    }

    public Integer getMaxConcurrentExceptions() {
        return maxConcurrentExceptions;
    }

    public void setMaxConcurrentExceptions(Integer maxConcurrentExceptions) {
        this.maxConcurrentExceptions = maxConcurrentExceptions;
    }

    public Boolean getIncludeStackTraces() {
        return includeStackTraces;
    }

    public void setIncludeStackTraces(Boolean includeStackTraces) {
        this.includeStackTraces = includeStackTraces;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Boolean getIncludeBackendCandidates() {
        return includeBackendCandidates;
    }

    public void setIncludeBackendCandidates(Boolean includeBackendCandidates) {
        this.includeBackendCandidates = includeBackendCandidates;
    }
}
