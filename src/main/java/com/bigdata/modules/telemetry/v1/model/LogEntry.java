package com.bigdata.modules.telemetry.v1.model;

import java.time.Instant;
import java.util.Map;

/**
 * 一条日志。
 *
 * <p>{@code timestamp} 来自行首时间戳解析；源没有配置时间戳格式且请求未带时间范围时为 null，
 * 不能用一个伪造时间填充。</p>
 */
public record LogEntry(Instant timestamp,
                       String stream,
                       String hostId,
                       String message,
                       Map<String, String> fields) {
}
