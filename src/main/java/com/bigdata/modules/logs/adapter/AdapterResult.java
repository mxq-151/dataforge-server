package com.bigdata.modules.logs.adapter;

/**
 * 适配器返回结果。
 *
 * <p>适配器负责「这一页原始内容 + 下一页从哪里继续」，不负责脱敏、关键字过滤与对外字段组装。
 * {@code content} 已经是按行数与字节预算裁剪过的内容，{@code nextOffset} 只按实际返回的行/字节推进，
 * 保证游标不重不漏。</p>
 */
public class AdapterResult {

    private String content = "";
    private int lineCount;
    private long nextOffset;
    private boolean hasMore;
    private Long totalLines;
    private boolean truncated;
    private boolean degraded;
    private String degradationReason;
    private String errorCode;
    private String message;
    private String resolvedHost;
    private String resolvedPath;

    public static AdapterResult of(String content, int lineCount, long nextOffset, boolean hasMore) {
        AdapterResult result = new AdapterResult();
        result.content = content == null ? "" : content;
        result.lineCount = lineCount;
        result.nextOffset = nextOffset;
        result.hasMore = hasMore;
        return result;
    }

    public static AdapterResult degraded(String errorCode, String message) {
        AdapterResult result = new AdapterResult();
        result.degraded = true;
        result.errorCode = errorCode;
        result.message = message;
        result.degradationReason = message;
        return result;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public long getNextOffset() {
        return nextOffset;
    }

    public void setNextOffset(long nextOffset) {
        this.nextOffset = nextOffset;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public Long getTotalLines() {
        return totalLines;
    }

    public void setTotalLines(Long totalLines) {
        this.totalLines = totalLines;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public String getDegradationReason() {
        return degradationReason;
    }

    public void setDegradationReason(String degradationReason) {
        this.degradationReason = degradationReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getResolvedHost() {
        return resolvedHost;
    }

    public void setResolvedHost(String resolvedHost) {
        this.resolvedHost = resolvedHost;
    }

    public String getResolvedPath() {
        return resolvedPath;
    }

    public void setResolvedPath(String resolvedPath) {
        this.resolvedPath = resolvedPath;
    }
}
