package com.bigdata.modules.telemetry.vo;

/** 取数元信息：分页游标、截断、降级与耗时。 */
public record QueryMeta(String cursor, boolean truncated, boolean degraded, long elapsedMs) {
}
