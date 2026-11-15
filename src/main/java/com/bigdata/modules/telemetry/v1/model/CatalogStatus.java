package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** 组件级目录状态：该组件的指标/日志清单是否已经定义。 */
public enum CatalogStatus {

    IMPLEMENTED("implemented"),
    DECLARED_NOT_IMPLEMENTED("declared_not_implemented"),
    NOT_APPLICABLE("not_applicable");

    private final String wire;

    CatalogStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
