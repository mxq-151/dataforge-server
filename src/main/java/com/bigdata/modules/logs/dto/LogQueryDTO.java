package com.bigdata.modules.logs.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一日志检索请求。
 *
 * <p>定位方式二选一：{@code sourceId} 直接指定，或 {@code serviceType + cluster} 由服务端匹配。
 * {@code ref} 承载业务引用（taskInstanceId / applicationId / containerId / appId / path / host）。</p>
 */
public class LogQueryDTO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private String componentRole;
    private String logKind;
    private Map<String, String> ref;
    private TimeRange timeRange;
    private String keyword;
    private String cursor;
    private Integer limit;
    private Integer maxBytes;

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

    public String getComponentRole() {
        return componentRole;
    }

    public void setComponentRole(String componentRole) {
        this.componentRole = componentRole;
    }

    public String getLogKind() {
        return logKind;
    }

    public void setLogKind(String logKind) {
        this.logKind = logKind;
    }

    public Map<String, String> getRef() {
        return ref;
    }

    public void setRef(Map<String, String> ref) {
        this.ref = ref;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(Integer maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** 时间范围（可选，用于上游支持按时间过滤的场景） */
    public static class TimeRange {
        private LocalDateTime start;
        private LocalDateTime end;

        public LocalDateTime getStart() {
            return start;
        }

        public void setStart(LocalDateTime start) {
            this.start = start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        public void setEnd(LocalDateTime end) {
            this.end = end;
        }
    }
}
