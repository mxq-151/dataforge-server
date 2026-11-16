package com.bigdata.modules.telemetry.service.metadata.model;

/**
 * provider 取到的一条原始配置。
 *
 * <p>provider 返回明文，核心统一脱敏；{@code sensitiveHint} 只能让字段更敏感，
 * 不能覆盖或关闭核心默认规则。</p>
 */
public record RawConfigEntry(String section,
                             String key,
                             String value,
                             String valueType,
                             boolean sensitiveHint,
                             String source) {
}
