package com.bigdata.modules.telemetry.v1.metadata.model;

import java.util.EnumSet;
import java.util.Set;

/** metadata 查询范围。请求里用 {@code version / config / both}，缺省 both。 */
public enum MetadataScope {

    VERSION("version"),
    CONFIG("config");

    private final String wire;

    MetadataScope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** {@code null/blank/both} 返回全部；未知值抛 {@link IllegalArgumentException}。 */
    public static Set<MetadataScope> parse(String scope) {
        if (scope == null || scope.isBlank() || "both".equalsIgnoreCase(scope.trim())) {
            return EnumSet.allOf(MetadataScope.class);
        }
        for (MetadataScope value : values()) {
            if (value.wire.equalsIgnoreCase(scope.trim())) {
                return EnumSet.of(value);
            }
        }
        throw new IllegalArgumentException("scope 必须是 version / config / both");
    }

    /** 请求回显用的 mode 字符串。 */
    public static String wire(Set<MetadataScope> scopes) {
        boolean version = scopes.contains(VERSION);
        boolean config = scopes.contains(CONFIG);
        if (version && config) {
            return "both";
        }
        return version ? VERSION.wire : CONFIG.wire;
    }
}
