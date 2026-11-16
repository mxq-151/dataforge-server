package com.bigdata.modules.telemetry.config.properties;

public class DsLimits {
    private int timeoutMs = 10000;
    private long maxBytes = 1048576L;
    private int maxLines = 5000;
    /** 单页请求的行数上限。 */
    private int pageSize = 1000;
    /** 最多翻页次数，防止异常场景无限分页。 */
    private int maxPages = 20;

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

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
