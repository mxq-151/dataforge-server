package com.bigdata.modules.telemetry.v1.runtime.spi;

import com.bigdata.modules.telemetry.v1.model.Target;

import java.util.List;

/** 一次运行时查询的归一化输入。 */
public record RuntimeQuerySpec(Target target,
                               String sourceMode,
                               String jobId,
                               List<String> families,
                               List<String> metrics,
                               List<String> components,
                               String componentRole,
                               boolean includeConfig,
                               int limit,
                               int checkpointHistory) {

    public RuntimeQuerySpec {
        families = families == null ? List.of() : List.copyOf(families);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        components = components == null ? List.of() : List.copyOf(components);
    }

    public RuntimeQuerySpec withJobId(String value) {
        return new RuntimeQuerySpec(target, sourceMode, value, families, metrics, components,
                componentRole, includeConfig, limit, checkpointHistory);
    }

    public RuntimeQuerySpec withComponentRole(String value) {
        return new RuntimeQuerySpec(target, sourceMode, jobId, families, metrics, components,
                value, includeConfig, limit, checkpointHistory);
    }

    public RuntimeQuerySpec withoutConfig() {
        return new RuntimeQuerySpec(target, sourceMode, jobId, families, metrics, components,
                componentRole, false, limit, checkpointHistory);
    }
}
