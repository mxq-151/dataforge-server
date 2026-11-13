package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.RuntimeSource;

import java.util.ArrayList;
import java.util.List;

/** 一次运行时查询的上下文（不是日志游标：指标是快照，天然无翻页） */
public class RuntimeQueryContext {

    private RuntimeSource source;
    private String jobId;
    private List<MetricFamily> families = new ArrayList<>();
    private List<String> explicitMetrics = new ArrayList<>();
    private boolean includeConfig;
    private boolean includeComponents = true;
    private boolean includeVertices = true;
    private int maxComponents;
    private int maxVertices;
    private int checkpointHistory;
    private long timeoutMs;
    private String user;
    /** 对比/强一致场景跳过缓存 */
    private boolean bypassCache;

    public boolean familyEnabled(MetricFamily family) {
        return families.contains(family);
    }

    public long restTimeoutMs() {
        int configured = source == null || source.getConfig().getOptions() == null
                ? 3000 : source.getConfig().getOptions().getRestTimeoutMs();
        return Math.max(500L, Math.min(timeoutMs, configured));
    }

    public RuntimeSource getSource() {
        return source;
    }

    public void setSource(RuntimeSource source) {
        this.source = source;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public List<MetricFamily> getFamilies() {
        return families;
    }

    public void setFamilies(List<MetricFamily> families) {
        this.families = families == null ? new ArrayList<>() : families;
    }

    public List<String> getExplicitMetrics() {
        return explicitMetrics;
    }

    public void setExplicitMetrics(List<String> explicitMetrics) {
        this.explicitMetrics = explicitMetrics == null ? new ArrayList<>() : explicitMetrics;
    }

    public boolean isIncludeConfig() {
        return includeConfig;
    }

    public void setIncludeConfig(boolean includeConfig) {
        this.includeConfig = includeConfig;
    }

    public boolean isIncludeComponents() {
        return includeComponents;
    }

    public void setIncludeComponents(boolean includeComponents) {
        this.includeComponents = includeComponents;
    }

    public boolean isIncludeVertices() {
        return includeVertices;
    }

    public void setIncludeVertices(boolean includeVertices) {
        this.includeVertices = includeVertices;
    }

    public int getMaxComponents() {
        return maxComponents;
    }

    public void setMaxComponents(int maxComponents) {
        this.maxComponents = maxComponents;
    }

    public int getMaxVertices() {
        return maxVertices;
    }

    public void setMaxVertices(int maxVertices) {
        this.maxVertices = maxVertices;
    }

    public int getCheckpointHistory() {
        return checkpointHistory;
    }

    public void setCheckpointHistory(int checkpointHistory) {
        this.checkpointHistory = checkpointHistory;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public boolean isBypassCache() {
        return bypassCache;
    }

    public void setBypassCache(boolean bypassCache) {
        this.bypassCache = bypassCache;
    }
}
