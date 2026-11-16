package com.bigdata.modules.telemetry.vo;

import java.time.Instant;

public record ConcurrentExceptionView(String exceptionName, String rootCause, String taskName,
                                      Instant timestamp, String taskManagerId, String endpoint,
                                      String stackTrace, boolean stackTraceTruncated,
                                      int stackTraceChars) {
}
