package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.List;

/** 作业发现结果；上游不可达时 degraded=true 并给出原因，而不是抛堆栈 */
public class RuntimeDiscoveryVO {

    private List<RuntimeJobVO> jobs = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private boolean degraded;
    private String degradationReason;
    private Long capturedAt;

    public List<RuntimeJobVO> getJobs() {
        return jobs;
    }

    public void setJobs(List<RuntimeJobVO> jobs) {
        this.jobs = jobs == null ? new ArrayList<>() : jobs;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
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

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }
}
