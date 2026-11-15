package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.BackendType;

/**
 * 状态数据后端 SPI。
 *
 * <p>用户新增一种指标存储时，实现本接口并注册为 Spring Bean 即可，不需要改目录与路由。</p>
 */
public interface MetricBackend {

    String backendId();

    BackendType backendType();

    BackendCapability evaluate(MetricQuerySpec spec);

    MetricQueryResult fetch(MetricQuerySpec spec) throws BackendException;
}
