package com.bigdata.modules.logs.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一日志检索响应。
 *
 * <p>{@code degraded=true} 表示上游不可达、超时或该源不具备该能力，此时 {@code errorCode}
 * 与 {@code message} 给出可读原因，调用方仍拿到 200 与结构化结果，不会看到堆栈。</p>
 */
public class LogQueryResultVO {

    private String sourceId;
    private String serviceType;
    private String cluster;
    private String componentRole;
    private String logKind;
    private String content = "";
    private List<String> lines = new ArrayList<>();
    private int lineCount;
    private int byteSize;
    private String nextCursor;
    private boolean hasMore;
    private boolean truncated;
    private boolean degraded;
    private String degradationReason;
    private String errorCode;
    private String resolvedHost;
    private String resolvedPath;
    private long elapsedMs;
    private String message;

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public List<String> getLines() {
        return lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines == null ? new ArrayList<>() : lines;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public int getByteSize() {
        return byteSize;
    }

    public void setByteSize(int byteSize) {
        this.byteSize = byteSize;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
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

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
