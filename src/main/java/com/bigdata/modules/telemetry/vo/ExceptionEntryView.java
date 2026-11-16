package com.bigdata.modules.telemetry.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExceptionEntryView(String exceptionName, String rootCause, String taskName,
                                 Instant timestamp, boolean serverTruncated,
                                 Map<String, String> failureLabels,
                                 String taskManagerId, String endpoint,
                                 String stackTrace, boolean stackTraceTruncated,
                                 int stackTraceChars,
                                 List<ConcurrentExceptionView> concurrentExceptions) {
}
