package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.NormalizedVersion;

/**
 * 一个服务组件的版本视图。
 *
 * <p>取不到版本时该条仍然存在：{@code availability=UNAVAILABLE|NOT_SUPPORTED}、
 * {@code rawVersion=null}、{@code normalized=null}、{@code reason} 必填；
 * 不填默认版本，也不从别的组件推断。</p>
 */
public record VersionView(MetadataTarget target,
                          String providerId,
                          String componentId,
                          String hostId,
                          String rawVersion,
                          NormalizedVersion normalized,
                          String buildHash,
                          String vendor,
                          String distribution,
                          String availability,
                          String reason,
                          String source,
                          long capturedAt) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";

    /** 版本无法解析：raw 原样保留，normalized 为 null。 */
    public static final String VERSION_UNPARSEABLE = "VERSION_UNPARSEABLE";
}
