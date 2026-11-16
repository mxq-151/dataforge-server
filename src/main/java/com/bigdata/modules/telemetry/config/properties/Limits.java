package com.bigdata.modules.telemetry.config.properties;

public class Limits {
    private int maxSeries = 5000;
    private int maxPointsPerSeries = 11000;
    private long maxResponseBytes = 8388608L;
    private int timeoutMs = 15000;

    public int getMaxSeries() {
        return maxSeries;
    }

    public void setMaxSeries(int maxSeries) {
        this.maxSeries = maxSeries;
    }

    public int getMaxPointsPerSeries() {
        return maxPointsPerSeries;
    }

    public void setMaxPointsPerSeries(int maxPointsPerSeries) {
        this.maxPointsPerSeries = maxPointsPerSeries;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
