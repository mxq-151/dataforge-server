package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.List;

/** Flink checkpoint 汇总与最近 N 次历史 */
public class RuntimeCheckpointVO {

    private boolean available;
    private String reason;
    private int completed;
    private int failed;
    private int inProgress;
    private int restored;
    private String latestFailedReason;
    private List<Entry> history = new ArrayList<>();

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(int completed) {
        this.completed = completed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public int getInProgress() {
        return inProgress;
    }

    public void setInProgress(int inProgress) {
        this.inProgress = inProgress;
    }

    public int getRestored() {
        return restored;
    }

    public void setRestored(int restored) {
        this.restored = restored;
    }

    public String getLatestFailedReason() {
        return latestFailedReason;
    }

    public void setLatestFailedReason(String latestFailedReason) {
        this.latestFailedReason = latestFailedReason;
    }

    public List<Entry> getHistory() {
        return history;
    }

    public void setHistory(List<Entry> history) {
        this.history = history == null ? new ArrayList<>() : history;
    }

    /** 单次 checkpoint */
    public static class Entry {
        private Long id;
        private String status;
        private Long triggerTimestamp;
        private Long completedTimestamp;
        private Long durationMs;
        private Long sizeBytes;
        private String failureMessage;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getTriggerTimestamp() {
            return triggerTimestamp;
        }

        public void setTriggerTimestamp(Long triggerTimestamp) {
            this.triggerTimestamp = triggerTimestamp;
        }

        public Long getCompletedTimestamp() {
            return completedTimestamp;
        }

        public void setCompletedTimestamp(Long completedTimestamp) {
            this.completedTimestamp = completedTimestamp;
        }

        public Long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(Long durationMs) {
            this.durationMs = durationMs;
        }

        public Long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getFailureMessage() {
            return failureMessage;
        }

        public void setFailureMessage(String failureMessage) {
            this.failureMessage = failureMessage;
        }
    }
}
