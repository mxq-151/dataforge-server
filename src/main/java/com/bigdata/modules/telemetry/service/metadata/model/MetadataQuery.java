package com.bigdata.modules.telemetry.service.metadata.model;

import com.bigdata.modules.telemetry.dto.MetadataTarget;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 一次 provider 取数请求：只针对单个 target，便于超时隔离与错误归属。
 *
 * <p>{@code timeout} 是 provider 必须遵守的上限（HTTP 超时 / JDBC queryTimeout），
 * 不允许无限等待。</p>
 */
public record MetadataQuery(MetadataTarget target,
                            Set<MetadataScope> scopes,
                            List<String> sections,
                            List<String> keys,
                            int maxConfigItems,
                            Duration timeout) {

    public MetadataQuery {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        sections = sections == null ? List.of() : List.copyOf(sections);
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    public boolean wantsVersion() {
        return scopes.contains(MetadataScope.VERSION);
    }

    public boolean wantsConfig() {
        return scopes.contains(MetadataScope.CONFIG);
    }
}
