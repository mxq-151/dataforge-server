package com.bigdata.modules.telemetry.v1.metadata.model;

import java.util.EnumSet;
import java.util.Set;

/** metadata 查询范围。请求里用 {@code version / config / metrics / both}，缺省 both。 */
public enum MetadataScope {

    VERSION("version"),
    CONFIG("config"),
    METRICS("metrics");

    private final String wire;

    MetadataScope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** {@code null/blank/both} 返回 version+config；metrics 单独返回；未知值抛异常。 */
    public static Set<MetadataScope> parse(String scope) {
        if (scope == null || scope.isBlank() || "both".equalsIgnoreCase(scope.trim())) {
            return EnumSet.of(VERSION, CONFIG);
        }
        for (MetadataScope value : values()) {
            if (value.wire.equalsIgnoreCase(scope.trim())) {
                return EnumSet.of(value);
            }
        }
        throw new IllegalArgumentException("scope 必须是 version / config / metrics / both");
    }

    /** 请求回显用的 mode 字符串。 */
    public static String wire(Set<MetadataScope> scopes) {
        boolean version = scopes.contains(VERSION);
        boolean config = scopes.contains(CONFIG);
        boolean metrics = scopes.contains(METRICS);
        if (metrics && !version && !config) {
            return METRICS.wire;
        }
        if (version && config && !metrics) {
            return "both";
        }
        return (version ? "version+" : "") + (config ? "config+" : "")
                + (metrics ? "metrics" : "");
    }
}
