package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.BackendWarning;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 统一的状态数据结果：{@code series[]}，每条带标签与采样点。
 *
 * <p>缺失采样直接省略，不补 0；counter 重置由查询函数（如 rate）处理，取数层不篡改值。</p>
 */
public record MetricQueryResult(List<Series> series, boolean truncated,
                                List<BackendWarning> warnings) {

    public record Series(Map<String, String> labels, List<Sample> samples) {
    }

    public record Sample(Instant timestamp, double value) {
    }

    public MetricQueryResult {
        series = series == null ? List.of() : List.copyOf(series);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isEmpty() {
        return series.isEmpty();
    }
}
