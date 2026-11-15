package com.bigdata.modules.telemetry.v1.metadata.model;

/**
 * 一条服务配置。
 *
 * <p>命中脱敏规则时 {@code sensitive=true}、{@code redacted=true}、{@code value="***"}；
 * 任何情况下都不回显明文。</p>
 */
public record ConfigEntryView(MetadataTarget target,
                              String providerId,
                              String section,
                              String key,
                              String value,
                              String valueType,
                              boolean sensitive,
                              boolean redacted,
                              String source,
                              long capturedAt) {

    /** 脱敏后的占位值，与运行时 config 保持一致。 */
    public static final String REDACTED = "***";
}
