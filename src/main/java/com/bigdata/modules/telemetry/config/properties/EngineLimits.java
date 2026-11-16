package com.bigdata.modules.telemetry.config.properties;

public class EngineLimits {
    private int timeoutMs = 10000;
    private long maxResponseBytes = 8388608L;
    private int maxSeries = 2000;

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxSeries() {
        return maxSeries;
    }

    public void setMaxSeries(int maxSeries) {
        this.maxSeries = maxSeries;
    }
}
