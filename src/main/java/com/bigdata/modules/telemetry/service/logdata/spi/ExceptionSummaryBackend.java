package com.bigdata.modules.telemetry.service.logdata.spi;

import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;

/**
 * 引擎结构化异常摘要后端。
 *
 * <p>与指标后端分开注册（{@code TelemetryBackendRegistry.exception(id)}）：同一个引擎可以
 * 同时提供两类能力（如 Flink），但实例、认证与故障域各自独立。</p>
 */
public interface ExceptionSummaryBackend {

    /** 后端实例 id，与指标/日志后端的命名空间一致（如 {@code flink-rest}）。 */
    String backendId();

    BackendCapability evaluate(ExceptionQuerySpec spec);

    ExceptionQueryResult fetch(ExceptionQuerySpec spec) throws BackendException;
}
