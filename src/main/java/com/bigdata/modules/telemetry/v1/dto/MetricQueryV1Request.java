package com.bigdata.modules.telemetry.v1.dto;

import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;

import java.util.Map;

/** 状态数据查询请求（catalog 模式只填 target / mode）。 */
public class MetricQueryV1Request {

    /** range / instant / catalog */
    private String mode;
    private Target target;
    private String metric;
    private Map<String, String> labelMatchers;
    /** instant 单点时间（RFC3339）。 */
    private String time;
    private TimeRange timeRange;
    /** range 步长（如 15s / PT15S）。 */
    private String step;
    private Map<String, String> ref;
    private Long timeoutMs;
    private Boolean includeBackendCandidates;
    /** 仅 counter + range 可用：按 rate(metric[step]) 返回每秒增量。 */
    private Boolean rate;

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

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Map<String, String> getLabelMatchers() {
        return labelMatchers;
    }

    public void setLabelMatchers(Map<String, String> labelMatchers) {
        this.labelMatchers = labelMatchers;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
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

    public Boolean getRate() {
        return rate;
    }

    public void setRate(Boolean rate) {
        this.rate = rate;
    }
}
