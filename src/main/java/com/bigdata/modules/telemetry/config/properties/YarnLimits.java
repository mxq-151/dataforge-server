package com.bigdata.modules.telemetry.config.properties;

public class YarnLimits {
    /** 单次 CLI 调用超时。 */
    private int timeoutMs = 30000;
    /** 最多捕获的 stdout 字节数，超出即截断。 */
    private long maxBytes = 1048576L;
    /** 同时运行的 yarn logs 子进程上限。 */
    private int maxConcurrent = 2;
    /** 等待并发槽位的最长时间，超时返回 RATE_LIMITED。 */
    private long acquireTimeoutMs = 2000L;
    /** node-local 回退时最多扫描的 NM 节点数，避免大集群逐个探测。 */
    private int maxNodes = 50;

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public long getAcquireTimeoutMs() {
        return acquireTimeoutMs;
    }

    public void setAcquireTimeoutMs(long acquireTimeoutMs) {
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }
}
