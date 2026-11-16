package com.bigdata.modules.telemetry.service.metricdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PromQL rate 选择器")
class PromqlSelectorBuilderTest {

    @Test
    @DisplayName("counter + range 生成 rate(metric[step])")
    void buildsRateSelectorForCounterRange() {
        MetricQuerySpec spec = spec("counter", QueryMode.RANGE, true);

        assertThat(PromqlSelectorBuilder.build(spec))
                .isEqualTo("rate(kafka_messages_total{service_type=\"kafka\","
                        + "component_id=\"kafka.broker\"}[30s])");
    }

    @Test
    @DisplayName("instant + rate 返回 INVALID_ARGUMENT")
    void rejectsRateForInstant() {
        MetricQuerySpec spec = spec("counter", QueryMode.INSTANT, true);

        assertThatThrownBy(() -> PromqlSelectorBuilder.build(spec))
                .isInstanceOf(BackendException.class)
                .hasMessageContaining("range")
                .satisfies(error -> assertThat(((BackendException) error).getCode())
                        .isEqualTo(CapabilityCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("gauge + rate 返回 INVALID_ARGUMENT")
    void rejectsRateForGauge() {
        MetricQuerySpec spec = spec("gauge", QueryMode.RANGE, true);

        assertThatThrownBy(() -> PromqlSelectorBuilder.build(spec))
                .isInstanceOf(BackendException.class)
                .hasMessageContaining("counter")
                .satisfies(error -> assertThat(((BackendException) error).getCode())
                        .isEqualTo(CapabilityCode.INVALID_ARGUMENT));
    }

    private static MetricQuerySpec spec(String type, QueryMode mode, boolean rate) {
        MetricDefinition definition = new MetricDefinition("kafka_messages_total", type,
                "messages", "broker", List.of(), List.of(), Map.of(), List.of());
        Instant start = Instant.parse("2026-11-15T13:00:00Z");
        Instant end = start.plusSeconds(300);
        return new MetricQuerySpec(new Target("kafka", "kafka.broker", "cluster"), definition,
                Map.of(), mode, start, start, end, Duration.ofSeconds(30), Map.of(), null, rate);
    }
}
