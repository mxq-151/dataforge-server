package com.bigdata.modules.telemetry.config.properties;

public class LogLimits {
    private int maxLines = 2000;
    private long maxBytes = 262144L;
    private long maxScanBytes = 67108864L;
    private int timeoutMs = 20000;

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long getMaxScanBytes() {
        return maxScanBytes;
    }

    public void setMaxScanBytes(long maxScanBytes) {
        this.maxScanBytes = maxScanBytes;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
