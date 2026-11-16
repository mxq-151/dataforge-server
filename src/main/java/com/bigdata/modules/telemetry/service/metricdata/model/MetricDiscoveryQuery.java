package com.bigdata.modules.telemetry.service.metricdata.model;

import com.bigdata.modules.telemetry.common.model.Target;

import java.time.Duration;
import java.util.Map;

/** 一次指标名发现请求；按 target + refs 限定范围，避免无界扫描。 */
public record MetricDiscoveryQuery(Target target, Map<String, String> refs,
                                   Duration timeout, int limit) {

    public MetricDiscoveryQuery {
        refs = refs == null ? Map.of() : Map.copyOf(refs);
        if (limit < 1) {
            limit = 1000;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
