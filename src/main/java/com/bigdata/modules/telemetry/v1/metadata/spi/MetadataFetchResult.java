package com.bigdata.modules.telemetry.v1.metadata.spi;

import java.util.List;

/** provider 返回的原始结果；脱敏与版本标准化由核心统一完成。 */
public record MetadataFetchResult(List<RawVersion> versions,
                                  List<RawConfigEntry> configs,
                                  List<String> warnings) {

    public MetadataFetchResult {
        versions = versions == null ? List.of() : List.copyOf(versions);
        configs = configs == null ? List.of() : List.copyOf(configs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static MetadataFetchResult empty() {
        return new MetadataFetchResult(List.of(), List.of(), List.of());
    }
}
