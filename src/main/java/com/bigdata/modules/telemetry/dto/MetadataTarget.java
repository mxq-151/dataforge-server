package com.bigdata.modules.telemetry.dto;

import com.bigdata.modules.telemetry.common.model.Target;

import java.util.Map;

/**
 * metadata 查询目标：五层模型三层标识 + 业务引用。
 *
 * <p>{@code ref} 承载 {@code cluster} / {@code namespace} / {@code appId} 等 provider 自行解释的
 * 业务引用；不改变 {@link Target} 的构造签名，避免影响既有调用点。</p>
 */
public record MetadataTarget(Target target, Map<String, String> ref) {

    public MetadataTarget {
        ref = ref == null ? Map.of() : Map.copyOf(ref);
    }

    public static MetadataTarget of(Target target) {
        return new MetadataTarget(target, Map.of());
    }
}
