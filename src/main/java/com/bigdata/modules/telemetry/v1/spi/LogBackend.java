package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.BackendType;

/**
 * 日志数据后端 SPI。
 *
 * <p>不可用时返回类型化 code 与 {@code requirements[]}，绝不返回空 {@code entries} 冒充可用。</p>
 */
public interface LogBackend {

    String backendId();

    BackendType backendType();

    BackendCapability evaluate(LogQuerySpec spec);

    LogQueryResult fetch(LogQuerySpec spec) throws BackendException;
}
