package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 名称匹配的边界：这里只过滤、不截断。
 *
 * <p>截断必须留给 {@code MetadataTelemetryQueryService}：provider 先按 limit 截断的话，
 * 命中多条会被截成「唯一命中」（静默取第一条），上层的 {@code RESULT_TRUNCATED} 标记
 * 也会永远为 false。</p>
 */
class TargetDiscoverySupportTest {

    private static final List<RawTarget> TARGETS = List.of(
            new RawTarget("spark", "spark.driver", Map.of("applicationId", "app-1"),
                    "etl_daily", "COMPLETED"),
            new RawTarget("spark", "spark.driver", Map.of("applicationId", "app-2"),
                    "etl_daily", "RUNNING"),
            new RawTarget("spark", "spark.driver", Map.of("applicationId", "app-3"),
                    "etl_daily", "COMPLETED"),
            new RawTarget("spark", "spark.driver", Map.of("applicationId", "app-4"),
                    "etl_other", "COMPLETED"));

    private static TargetDiscoveryQuery query(String taskName, String matchMode) {
        return new TargetDiscoveryQuery(taskName, matchMode, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("列表查询：返回全部，不截断")
    void listModeReturnsAll() {
        assertThat(TargetDiscoverySupport.filter(TARGETS, query(null, "auto"))).hasSize(4);
        assertThat(TargetDiscoverySupport.filter(TARGETS, query("  ", "auto"))).hasSize(4);
    }

    @Test
    @DisplayName("按名字精确命中：3 条同名全部返回，不截断")
    void exactNameReturnsAll() {
        assertThat(TargetDiscoverySupport.filter(TARGETS, query("etl_daily", "auto")))
                .hasSize(3)
                .allSatisfy(target -> assertThat(target.name()).isEqualTo("etl_daily"));
    }

    @Test
    @DisplayName("模糊命中：全部返回，避免把歧义截成唯一命中")
    void fuzzyReturnsAll() {
        assertThat(TargetDiscoverySupport.filter(TARGETS, query("etl", "fuzzy"))).hasSize(4);
    }

    @Test
    @DisplayName("精确优先：有精确命中时不再模糊兜底")
    void exactWinsOverFuzzy() {
        assertThat(TargetDiscoverySupport.filter(TARGETS, query("etl_daily", "auto"))).hasSize(3);
    }

    @Test
    @DisplayName("matchMode=exact：精确无命中直接返回空，不模糊兜底")
    void exactModeDoesNotFallBackToFuzzy() {
        assertThat(TargetDiscoverySupport.filter(TARGETS, query("etl", "exact"))).isEmpty();
    }
}
