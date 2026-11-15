package com.bigdata.modules.telemetry.v1.dto;

import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;

import java.util.List;
import java.util.Map;

/** 日志查询请求（catalog 模式只填 target / mode）。 */
public class LogQueryV1Request {

    /** data / catalog */
    private String mode;
    private Target target;
    /** log.service（服务自身日志） / log.task（任务日志）；data 模式必填。 */
    private String dataType;
    /** stdout / stderr / slowquery */
    private String stream;
    private TimeRange timeRange;
    private List<String> keywords;
    private List<String> excludeKeywords;
    /** all / any；默认 all。 */
    private String matchMode;
    private Boolean regex;
    private Boolean caseSensitive;
    private String cursor;
    private Integer limit;
    private Long maxBytes;
    private Map<String, String> ref;
    private Long timeoutMs;
    private Boolean includeBackendCandidates;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords;
    }

    public void setExcludeKeywords(List<String> excludeKeywords) {
        this.excludeKeywords = excludeKeywords;
    }

    public String getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(String matchMode) {
        this.matchMode = matchMode;
    }

    public Boolean getRegex() {
        return regex;
    }

    public void setRegex(Boolean regex) {
        this.regex = regex;
    }

    public Boolean getCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(Boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(Long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public Map<String, String> getRef() {
        return ref;
    }

    public void setRef(Map<String, String> ref) {
        this.ref = ref;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Boolean getIncludeBackendCandidates() {
        return includeBackendCandidates;
    }

    public void setIncludeBackendCandidates(Boolean includeBackendCandidates) {
        this.includeBackendCandidates = includeBackendCandidates;
    }
}
