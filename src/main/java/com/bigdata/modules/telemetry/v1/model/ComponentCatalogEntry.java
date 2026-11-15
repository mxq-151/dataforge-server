package com.bigdata.modules.telemetry.v1.model;

import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/**
 * 一个服务组件的目录条目。
 *
 * <p>{@code metricCatalogStatus} 用于区分「已经定义指标清单」与「只声明尚未定义」：
 * 后者查询返回 NOT_IMPLEMENTED + {@code metricRequirements}，不编造指标名。</p>
 */
public record ComponentCatalogEntry(String serviceType,
                                    String componentId,
                                    List<MetricDefinition> metrics,
                                    List<LogStreamDefinition> logStreams,
                                    List<EngineEvidenceDefinition> engineEvidence,
                                    CatalogStatus metricCatalogStatus,
                                    List<Requirement> metricRequirements) {

    /** 引擎结构化证据（如 Flink 异常摘要）：只在目录声明，不进日志接口。 */
    public record EngineEvidenceDefinition(String name, List<BackendBinding> supportedBackends) {
    }
}
