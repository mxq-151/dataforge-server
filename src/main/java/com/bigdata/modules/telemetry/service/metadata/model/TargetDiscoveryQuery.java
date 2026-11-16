package com.bigdata.modules.telemetry.service.metadata.model;

import java.time.Duration;

/**
 * 目标发现请求。
 *
 * <p>{@code matchMode} 取 {@code auto / exact / fuzzy}：auto 先精确、无精确再模糊；
 * {@code taskName} 为空时返回清单，不做名称过滤。</p>
 *
 * <p><b>不带 limit</b>：provider 必须返回全部匹配，截断与 {@code RESULT_TRUNCATED} 标记
 * 统一由 {@code MetadataTelemetryQueryService} 做。provider 先截断会让上层误以为结果完整，
 * 也会把「同名多条」截成唯一命中。</p>
 */
public record TargetDiscoveryQuery(String taskName,
                                   String matchMode,
                                   Duration timeout) {

    public TargetDiscoveryQuery {
        matchMode = matchMode == null || matchMode.isBlank() ? "auto" : matchMode.trim();
    }

    public boolean allowsFuzzy() {
        return !"exact".equalsIgnoreCase(matchMode);
    }
}
