package com.bigdata.modules.logs.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨服务串联查询响应。
 *
 * <p>单段失败不影响其他段：每段带 {@code status}（ok / degraded / disabled / denied / busy / error），
 * 便于诊断时「哪一段拿不到、为什么」。</p>
 */
public class LogChainResultVO {

    private List<SegmentVO> segments = new ArrayList<>();
    private int totalBytes;
    private boolean truncated;

    public List<SegmentVO> getSegments() {
        return segments;
    }

    public void setSegments(List<SegmentVO> segments) {
        this.segments = segments == null ? new ArrayList<>() : segments;
    }

    public int getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(int totalBytes) {
        this.totalBytes = totalBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    /** 串联中的一段结果 */
    public static class SegmentVO {
        private int index;
        private String sourceId;
        private String serviceType;
        private String componentRole;
        private String logKind;
        private String status;
        private String content = "";
        private int lineCount;
        private int byteSize;
        private boolean degraded;
        private String errorCode;
        private String message;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

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

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
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

        public int getByteSize() {
            return byteSize;
        }

        public void setByteSize(int byteSize) {
            this.byteSize = byteSize;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public void setDegraded(boolean degraded) {
            this.degraded = degraded;
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
    }
}
