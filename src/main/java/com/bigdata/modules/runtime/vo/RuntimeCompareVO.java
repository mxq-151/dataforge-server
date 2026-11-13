package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间点对比：同一作业两次快照之间累计型指标的增量与每秒速率。
 *
 * <p>REST 接口不保存历史指标，因此对比基于本服务进程内最近两次快照；
 * 窗口内没有更早快照时 {@code comparable=false} 并说明原因。</p>
 */
public class RuntimeCompareVO {

    private String sourceId;
    private String serviceType;
    private String jobId;
    private boolean comparable;
    private String reason;
    private Long fromCapturedAt;
    private Long toCapturedAt;
    private Long windowSeconds;
    private List<Row> rows = new ArrayList<>();

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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public boolean isComparable() {
        return comparable;
    }

    public void setComparable(boolean comparable) {
        this.comparable = comparable;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getFromCapturedAt() {
        return fromCapturedAt;
    }

    public void setFromCapturedAt(Long fromCapturedAt) {
        this.fromCapturedAt = fromCapturedAt;
    }

    public Long getToCapturedAt() {
        return toCapturedAt;
    }

    public void setToCapturedAt(Long toCapturedAt) {
        this.toCapturedAt = toCapturedAt;
    }

    public Long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public List<Row> getRows() {
        return rows;
    }

    public void setRows(List<Row> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    /** 一行增量 */
    public static class Row {
        private String component;
        private String componentType;
        private String family;
        private String name;
        private String unit;
        private Double from;
        private Double to;
        private Double delta;
        private Double perSecond;

        public String getComponent() {
            return component;
        }

        public void setComponent(String component) {
            this.component = component;
        }

        public String getComponentType() {
            return componentType;
        }

        public void setComponentType(String componentType) {
            this.componentType = componentType;
        }

        public String getFamily() {
            return family;
        }

        public void setFamily(String family) {
            this.family = family;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public Double getFrom() {
            return from;
        }

        public void setFrom(Double from) {
            this.from = from;
        }

        public Double getTo() {
            return to;
        }

        public void setTo(Double to) {
            this.to = to;
        }

        public Double getDelta() {
            return delta;
        }

        public void setDelta(Double delta) {
            this.delta = delta;
        }

        public Double getPerSecond() {
            return perSecond;
        }

        public void setPerSecond(Double perSecond) {
            this.perSecond = perSecond;
        }
    }
}
