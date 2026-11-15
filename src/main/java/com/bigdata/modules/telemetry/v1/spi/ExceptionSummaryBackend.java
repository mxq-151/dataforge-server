package com.bigdata.modules.telemetry.v1.spi;

/**
 * 引擎结构化异常摘要后端。
 *
 * <p>由已注册的 {@code MetricBackend} 实例额外实现；不新建注册表，实例 id / 认证 / 熔断与
 * 现有后端机制保持一致。</p>
 */
public interface ExceptionSummaryBackend {

    BackendCapability evaluate(ExceptionQuerySpec spec);

    ExceptionQueryResult fetch(ExceptionQuerySpec spec) throws BackendException;
}
