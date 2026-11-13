package com.bigdata.modules.logs.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨服务串联查询请求（如 DS 任务日志 → YARN AM 聚合日志 → Spark driver 日志）。
 *
 * <p>顶层字段作为默认值，每段可单独覆盖；最多段数与总预算由配置限制。</p>
 */
public class LogChainRequestDTO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private Integer maxBytes;
    private List<Segment> segments = new ArrayList<>();

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

    public Integer getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(Integer maxBytes) {
        this.maxBytes = maxBytes;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public void setSegments(List<Segment> segments) {
        this.segments = segments;
    }

    /** 串联中的一段 */
    public static class Segment {
        private String sourceId;
        private String serviceType;
        private String cluster;
        private String componentRole;
        private String logKind;
        private Map<String, String> ref;
        private String keyword;

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

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }
    }
}
