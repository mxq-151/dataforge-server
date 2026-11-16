package com.bigdata.modules.telemetry.config.properties;

public class EsLimits {
    private int timeoutMs = 15000;
    private long maxResponseBytes = 8388608L;
    private long mappingCacheTtlMs = 60000L;
    private int pitKeepAliveSeconds = 120;

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

    public long getMappingCacheTtlMs() {
        return mappingCacheTtlMs;
    }

    public void setMappingCacheTtlMs(long mappingCacheTtlMs) {
        this.mappingCacheTtlMs = mappingCacheTtlMs;
    }

    public int getPitKeepAliveSeconds() {
        return pitKeepAliveSeconds;
    }

    public void setPitKeepAliveSeconds(int pitKeepAliveSeconds) {
        this.pitKeepAliveSeconds = pitKeepAliveSeconds;
    }
}
