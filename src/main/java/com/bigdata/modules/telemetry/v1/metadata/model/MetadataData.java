package com.bigdata.modules.telemetry.v1.metadata.model;

import com.bigdata.modules.telemetry.v1.model.BackendWarning;

import java.util.List;

/**
 * metadata 查询结果数据。
 *
 * <p>版本取不到时仍保留一条 {@link VersionView}（带 availability / reason）；
 * 配置取不到时不生成伪造条目，失败原因进 {@code providers.attempts[]} 与 {@code warnings}。</p>
 *
 * <p>尝试记录只放在 {@code providers.attempts[]} 一处，避免同一份数据在响应里出现两次。</p>
 */
public record MetadataData(String scope,
                           long capturedAt,
                           List<VersionView> versions,
                           List<ConfigEntryView> configs,
                           List<BackendWarning> warnings) {

    public MetadataData {
        versions = versions == null ? List.of() : List.copyOf(versions);
        configs = configs == null ? List.of() : List.copyOf(configs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
