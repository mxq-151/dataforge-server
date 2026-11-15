package com.bigdata.modules.telemetry.v1.vo;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.LogEntry;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.routing.BackendCandidate;
import com.bigdata.modules.telemetry.v1.routing.BackendSkip;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 两个 v1 查询接口的统一响应外壳。
 *
 * <p>{@code outcome} 四态：ok / unavailable / not_implemented / failed；不可用与未实现时
 * {@code data} 恒为 null。{@code backend} 即使失败也返回，保留实际尝试与跳过原因。</p>
 */
public record TelemetryQueryV1Response(String outcome,
                                       boolean retryable,
                                       Target target,
                                       String mode,
                                       BackendView backend,
                                       Object data,
                                       ErrorView error,
                                       MetaView meta) {

    public record BackendView(Selected selected,
                              String preferred,
                              boolean fallback,
                              List<BackendSkip> skipped,
                              List<Attempt> attempts,
                              List<BackendCandidate> candidates) {
    }

    public record Selected(String backendId, String backendType, String queryMode, boolean fallback) {
    }

    public record Attempt(String backendId, String backendType, CapabilityCode code,
                          long elapsedMs, boolean retryable) {
    }

    public record ErrorView(CapabilityCode code, String message, List<Requirement> requirements) {
    }

    public record MetaView(long elapsedMs, boolean partial, boolean degraded,
                           List<BackendWarning> warnings, Long retryAfterMs,
                           String collectedAt, String cursor, boolean truncated, ScanView scan) {
    }

    public record ScanView(int files, long bytes, long lines, int matched) {
    }

    public record MetricData(String mode, String resultState,
                             List<SeriesView> series, int seriesCount) {
    }

    public record SeriesView(MetricMeta metric, Map<String, String> labels,
                             List<SampleView> samples, int sampleCount) {
    }

    public record MetricMeta(String name, String type, String unit) {
    }

    public record SampleView(Instant timestamp, double value) {
    }

    public record LogData(String dataType, String stream, String resultState,
                          List<LogEntry> entries, int entryCount) {
    }

    /**
     * 目录快照。{@code metadata} 是「服务版本与配置」的目录，只增字段：
     * {@code catalogVersion} 与 {@code components} 的结构与含义保持不变。
     */
    public record CatalogData(String catalogVersion, List<ComponentCatalogEntry> components,
                              List<MetadataCatalogEntry> metadata) {
    }

    /**
     * 引擎异常摘要。异常是结构化证据，不拍平成日志行。
     *
     * <p>{@code empty=true} 表示「查询成功且服务端异常历史为空」，与取不到（outcome != ok）严格区分。
     * 触达任一上限时 {@code truncated=true} 且 {@code truncatedCode=RESULT_TRUNCATED}。</p>
     */
    public record ExceptionData(String jobId, String resultState, boolean empty,
                                List<ExceptionEntryView> exceptions, int exceptionCount,
                                int concurrentExceptionCount, boolean truncated,
                                CapabilityCode truncatedCode, boolean serverTruncated,
                                int requestedMaxExceptions,
                                List<BackendWarning> warnings) {
    }

    public record ExceptionEntryView(String exceptionName, String rootCause, String taskName,
                                     Instant timestamp, boolean serverTruncated,
                                     Map<String, String> failureLabels,
                                     String taskManagerId, String endpoint,
                                     String stackTrace, boolean stackTraceTruncated,
                                     int stackTraceChars,
                                     List<ConcurrentExceptionView> concurrentExceptions) {
    }

    public record ConcurrentExceptionView(String exceptionName, String rootCause, String taskName,
                                          Instant timestamp, String taskManagerId, String endpoint,
                                          String stackTrace, boolean stackTraceTruncated,
                                          int stackTraceChars) {
    }

}
