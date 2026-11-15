package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 一次状态数据查询的统一输入。
 *
 * <p>后端只接受归一化后的结构；PromQL 选择器、ES DSL 等翻译都在后端内部完成。</p>
 */
public record MetricQuerySpec(Target target,
                              MetricDefinition definition,
                              Map<String, String> labelMatchers,
                              QueryMode mode,
                              Instant time,
                              Instant start,
                              Instant end,
                              Duration step,
                              Map<String, String> refs,
                              Duration timeout,
                              boolean rate) {

    /** 兼容既有调用：默认不启用 rate，原始 counter 原样返回。 */
    public MetricQuerySpec(Target target, MetricDefinition definition,
                           Map<String, String> labelMatchers, QueryMode mode, Instant time,
                           Instant start, Instant end, Duration step,
                           Map<String, String> refs, Duration timeout) {
        this(target, definition, labelMatchers, mode, time, start, end, step, refs, timeout, false);
    }

    public MetricQuerySpec {
        labelMatchers = labelMatchers == null ? Map.of() : Map.copyOf(labelMatchers);
        refs = refs == null ? Map.of() : Map.copyOf(refs);
    }

    public TimeRange timeRange() {
        return new TimeRange(start, end);
    }
}
