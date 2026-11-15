package com.bigdata.modules.telemetry.v1.model;

import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/**
 * 日志流目录定义：每个流属于一个 {@link LogDataType}，并声明该分类下可用的后端。
 *
 * <p>同一 stream 名可以同时出现在 {@code log.service} 与 {@code log.task} 下，但两边的
 * {@code supportedBackends} 必须严格分离；查询时必须带 {@code dataType} 定位到唯一一条定义。</p>
 *
 * <p>{@code requirements} 用于声明型日志流（如 slowquery）：查询返回 NOT_IMPLEMENTED 时
 * 直接给出需要补齐的配置键或接口，避免调用方自行猜测。</p>
 */
public record LogStreamDefinition(LogDataType dataType,
                                 String stream,
                                 List<String> requiredRefs,
                                 List<String> requiredAnyRefs,
                                 List<BackendBinding> supportedBackends,
                                 List<Requirement> requirements) {

    public LogStreamDefinition {
        requiredRefs = requiredRefs == null ? List.of() : List.copyOf(requiredRefs);
        requiredAnyRefs = requiredAnyRefs == null ? List.of() : List.copyOf(requiredAnyRefs);
        supportedBackends = supportedBackends == null ? List.of() : List.copyOf(supportedBackends);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
