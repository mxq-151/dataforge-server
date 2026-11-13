package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 适配器查询上下文：只包含一次查询所需的已校验参数。
 */
public class LogQueryContext {

    private LogSource source;
    private LogKind logKind = LogKind.RUNTIME;
    private ComponentRole role = ComponentRole.GENERIC;
    /** 业务引用：taskInstanceId / applicationId / containerId / appId / path / host 等 */
    private Map<String, String> ref = new HashMap<>();
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String keyword;
    /** 已解码的游标偏移（行或字节，具体由适配器解释） */
    private long offset;
    private int maxLines;
    private int maxBytes;
    private long timeoutMs;
    private String user;

    /** 按顺序取第一个非空的 ref 值，便于兼容多版本字段名 */
    public String ref(String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = ref.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public LogSource getSource() {
        return source;
    }

    public void setSource(LogSource source) {
        this.source = source;
    }

    public LogKind getLogKind() {
        return logKind;
    }

    public void setLogKind(LogKind logKind) {
        this.logKind = logKind;
    }

    public ComponentRole getRole() {
        return role;
    }

    public void setRole(ComponentRole role) {
        this.role = role;
    }

    public Map<String, String> getRef() {
        return ref;
    }

    public void setRef(Map<String, String> ref) {
        this.ref = ref == null ? new HashMap<>() : new HashMap<>(ref);
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
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
}
