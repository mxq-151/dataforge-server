package com.bigdata.modules.telemetry.v1.backend.localdisk;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.LocalDiskConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.LogSourceConfig;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogFilter;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskLogBackendTest {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final Target TARGET = new Target("flink", "flink.taskmanager", "tm-1");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("时间范围 + 关键词：返回范围内匹配行，并暴露扫描统计")
    void timeRangeAndKeyword() throws IOException {
        Path file = writeLog();
        LocalDiskLogBackend backend = backend(file, PATTERN);
        LogQuerySpec spec = spec(range("2026-11-15T01:00:04Z", "2026-11-15T01:00:15Z"),
                List.of("checkpoint"), List.of(), null, 10);

        assertThat(backend.evaluate(spec).isAvailable()).isTrue();
        LogQueryResult result = backend.fetch(spec);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries()).extracting(entry -> entry.message())
                .containsExactly("2026-11-15 01:00:05.000 ERROR checkpoint failed",
                        "2026-11-15 01:00:10.000 INFO checkpoint ok");
        assertThat(result.truncated()).isFalse();
        assertThat(result.scan().files()).isEqualTo(1);
        assertThat(result.scan().lines()).isEqualTo(4);
        assertThat(result.scan().bytes()).isPositive();
        assertThat(result.entries().get(0).timestamp()).isEqualTo(Instant.parse("2026-11-15T01:00:05Z"));
    }

    @Test
    @DisplayName("limit 触达时返回游标，游标翻页不重复也不丢行")
    void pagination() throws IOException {
        Path file = writeLog();
        LocalDiskLogBackend backend = backend(file, PATTERN);
        LogQuerySpec first = spec(null, List.of("checkpoint"), List.of(), null, 1);

        LogQueryResult page1 = backend.fetch(first);
        assertThat(page1.entries()).hasSize(1);
        assertThat(page1.truncated()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        LogQuerySpec second = spec(null, List.of("checkpoint"), List.of(), page1.nextCursor(), 1);
        LogQueryResult page2 = backend.fetch(second);
        assertThat(page2.entries()).hasSize(1);
        assertThat(page2.entries().get(0).message()).contains("checkpoint ok");

        LogQuerySpec third = spec(null, List.of("checkpoint"), List.of(), page2.nextCursor(), 1);
        LogQueryResult page3 = backend.fetch(third);
        assertThat(page3.entries()).isEmpty();
        assertThat(page3.truncated()).isFalse();
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    @DisplayName("排除词优先：命中排除词的行被丢弃")
    void excludeKeyword() throws IOException {
        Path file = writeLog();
        LocalDiskLogBackend backend = backend(file, PATTERN);
        LogQuerySpec spec = spec(null, List.of(), List.of("DEBUG"), null, 10);

        LogQueryResult result = backend.fetch(spec);
        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries()).noneMatch(entry -> entry.message().contains("DEBUG"));
    }

    @Test
    @DisplayName("按文件 mtime 裁剪：文件早于范围起点时直接空结果，不报错")
    void mtimePruning() throws IOException {
        Path file = writeLog();
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-14T00:00:00Z")));
        LocalDiskLogBackend backend = backend(file, PATTERN);

        LogQueryResult result = backend.fetch(
                spec(range("2026-11-15T00:00:00Z", "2026-11-15T02:00:00Z"), List.of(), List.of(), null, 10));

        assertThat(result.entries()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.scan().files()).isZero();
    }

    @Test
    @DisplayName("未配置时间戳格式 + 时间范围：能力判定返回 TIME_FILTER_UNSUPPORTED")
    void timeFilterUnsupported() throws IOException {
        Path file = writeLog();
        LocalDiskLogBackend backend = backend(file, "");

        BackendCapability capability = backend.evaluate(
                spec(range("2026-11-15T00:00:00Z", "2026-11-15T02:00:00Z"), List.of(), List.of(), null, 10));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.TIME_FILTER_UNSUPPORTED);
        assertThat(capability.requirements()).isNotEmpty();
    }

    @Test
    @DisplayName("过滤条件变化后继续用旧游标：CURSOR_MISMATCH")
    void cursorMismatch() throws IOException {
        Path file = writeLog();
        LocalDiskLogBackend backend = backend(file, PATTERN);
        LogQueryResult page = backend.fetch(spec(null, List.of("checkpoint"), List.of(), null, 1));

        LogQuerySpec changed = spec(null, List.of("noise"), List.of(), page.nextCursor(), 1);
        assertThatThrownBy(() -> backend.fetch(changed))
                .isInstanceOf(BackendException.class)
                .extracting(e -> ((BackendException) e).getCode())
                .isEqualTo(CapabilityCode.CURSOR_MISMATCH);
    }

    @Test
    @DisplayName("扫描预算耗尽：返回已扫描结果 + truncated + 可继续游标")
    void scanBudgetTruncates() throws IOException {
        Path file = writeLog();
        LocalDiskConfig config = config(file, PATTERN);
        config.getLimits().setMaxScanBytes(10);
        LocalDiskLogBackend backend = new LocalDiskLogBackend("local-disk", config, new ObjectMapper());

        LogQueryResult result = backend.fetch(spec(null, List.of(), List.of(), null, 100));

        assertThat(result.truncated()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(result.scan().bytes()).isGreaterThanOrEqualTo(10);
        assertThat(result.scan().files()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 辅助

    private Path writeLog() throws IOException {
        Path file = tempDir.resolve("flink-taskmanager.out");
        List<String> lines = List.of(
                "2026-11-15 01:00:00.000 INFO start",
                "2026-11-15 01:00:05.000 ERROR checkpoint failed",
                "2026-11-15 01:00:10.000 INFO checkpoint ok",
                "2026-11-15 01:00:20.000 DEBUG noise");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private LocalDiskLogBackend backend(Path file, String pattern) {
        return new LocalDiskLogBackend("local-disk", config(file, pattern), new ObjectMapper());
    }

    private LocalDiskConfig config(Path file, String pattern) {
        LocalDiskConfig config = new LocalDiskConfig();
        config.setId("local-disk");
        config.setAllowRootDirs(List.of(tempDir.toString()));
        LogSourceConfig source = new LogSourceConfig();
        source.setServiceType("flink");
        source.setComponentId("flink.taskmanager");
        source.setHostId("tm-1");
        source.setStream("stdout");
        source.setPath(file.toString());
        source.setTimestampPattern(pattern);
        source.setZone("UTC");
        config.setSources(List.of(source));
        return config;
    }

    private LogQuerySpec spec(TimeRange range, List<String> keywords, List<String> exclude,
                              String cursor, int limit) {
        return new LogQuerySpec(TARGET, LogDataType.SERVICE, "stdout", range,
                new LogFilter(keywords, exclude, LogFilter.MatchMode.ALL, false, false),
                cursor, limit, 1024 * 1024, Map.of(), null);
    }

    private TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }
}
