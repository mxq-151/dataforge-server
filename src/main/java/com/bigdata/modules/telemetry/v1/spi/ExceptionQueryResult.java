package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.BackendWarning;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 引擎异常摘要的结构化结果。
 *
 * <p>字段来自引擎原始响应，不做扁平化：{@code serverTruncated} 映射服务端的
 * {@code exceptionHistory.truncated}，{@code limitTruncated} 标记调用方/配置上限触顶；
 * {@code rootCause} 从 stacktrace 的 {@code Caused by:} 行提取，没有 cause 时回退
 * {@code exceptionName}，不凭空编造。</p>
 */
public record ExceptionQueryResult(List<ExceptionRecord> exceptions,
                                   int concurrentExceptionCount,
                                   boolean serverTruncated,
                                   boolean limitTruncated,
                                   int requestedMaxExceptions,
                                   List<BackendWarning> warnings) {

    public ExceptionQueryResult {
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isEmpty() {
        return exceptions.isEmpty();
    }

    public boolean truncated() {
        return serverTruncated || limitTruncated;
    }

    public record ExceptionRecord(String exceptionName,
                                  String rootCause,
                                  String stackTrace,
                                  Instant timestamp,
                                  String taskName,
                                  String taskManagerId,
                                  String endpoint,
                                  Map<String, String> failureLabels,
                                  boolean serverTruncated,
                                  boolean stackTraceTruncated,
                                  int stackTraceChars,
                                  List<ConcurrentExceptionRecord> concurrentExceptions) {

        public ExceptionRecord {
            failureLabels = failureLabels == null ? Map.of() : Map.copyOf(failureLabels);
            concurrentExceptions = concurrentExceptions == null
                    ? List.of() : List.copyOf(concurrentExceptions);
        }
    }

    public record ConcurrentExceptionRecord(String exceptionName,
                                            String rootCause,
                                            String stackTrace,
                                            Instant timestamp,
                                            String taskName,
                                            String taskManagerId,
                                            String endpoint,
                                            Map<String, String> failureLabels,
                                            boolean stackTraceTruncated,
                                            int stackTraceChars) {

        public ConcurrentExceptionRecord {
            failureLabels = failureLabels == null ? Map.of() : Map.copyOf(failureLabels);
        }
    }
}
