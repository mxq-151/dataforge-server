package com.bigdata.modules.telemetry.service.metadata.model;

/**
 * provider 取到的原始版本信息。
 *
 * <p>{@code rawVersion} 必须是上游原值，不做加工；标准化与解析失败标记由核心完成。
 * {@code source} 说明真实取数路径（如 {@code clickhouse:version()}），便于排障。</p>
 */
public record RawVersion(String componentId,
                         String hostId,
                         String rawVersion,
                         String buildHash,
                         String vendor,
                         String distribution,
                         String source) {
}
