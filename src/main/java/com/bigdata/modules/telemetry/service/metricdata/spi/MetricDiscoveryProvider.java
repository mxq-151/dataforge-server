package com.bigdata.modules.telemetry.service.metricdata.spi;

import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;

/**
 * 指标名发现 provider：与 MetricBackend / LogBackend 同一注册风格。
 *
 * <p>新增可发现引擎只需注册一个 Bean；order 越小越优先。</p>
 */
public interface MetricDiscoveryProvider {

    String providerId();

    String providerType();

    default int order() {
        return 100;
    }

    default boolean enabled() {
        return true;
    }

    boolean supports(Target target);

    MetricDiscoveryResult discover(MetricDiscoveryQuery query);
}
