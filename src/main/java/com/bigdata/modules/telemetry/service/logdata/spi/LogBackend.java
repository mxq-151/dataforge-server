package com.bigdata.modules.telemetry.service.logdata.spi;

import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;

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
