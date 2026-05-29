package com.bigdata.modules.system.vo;

import java.io.Serializable;

public class AuditStatisticsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long totalOperations;
    private long sensitiveOperations;
    private long errorOperations;
    private long todayOperations;
    private long weekOperations;
    private long avgDurationMs;

    public long getTotalOperations() {
        return totalOperations;
    }

    public void setTotalOperations(long totalOperations) {
        this.totalOperations = totalOperations;
    }

    public long getSensitiveOperations() {
        return sensitiveOperations;
    }

    public void setSensitiveOperations(long sensitiveOperations) {
        this.sensitiveOperations = sensitiveOperations;
    }

    public long getErrorOperations() {
        return errorOperations;
    }

    public void setErrorOperations(long errorOperations) {
        this.errorOperations = errorOperations;
    }

    public long getTodayOperations() {
        return todayOperations;
    }

    public void setTodayOperations(long todayOperations) {
        this.todayOperations = todayOperations;
    }

    public long getWeekOperations() {
        return weekOperations;
    }

    public void setWeekOperations(long weekOperations) {
        this.weekOperations = weekOperations;
    }

    public long getAvgDurationMs() {
        return avgDurationMs;
    }

    public void setAvgDurationMs(long avgDurationMs) {
        this.avgDurationMs = avgDurationMs;
    }
}
