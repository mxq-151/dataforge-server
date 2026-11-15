package com.bigdata.modules.telemetry.v1.runtime.dto;

import com.bigdata.modules.telemetry.v1.model.Target;

/**
 * 时间点对比请求。
 *
 * <p>与进程内更早的一份快照做差；历史不持久化，重启即丢，多实例各自独立不可比。</p>
 */
public class CompareV1Request {

    private Target target;
    private String sourceMode;
    private String jobId;
    /** 允许的最远历史窗口（秒），默认取配置（60）。 */
    private Long windowSeconds;

    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
