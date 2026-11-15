package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 目录中的后端声明状态。
 *
 * <ul>
 *   <li>{@code implemented}：有实现；当前配置不满足时查询返回 UNAVAILABLE，不返回空数据；</li>
 *   <li>{@code declared_not_implemented}：只声明，查询返回 NOT_IMPLEMENTED + requirements；</li>
 *   <li>{@code runtime_unavailable}：有实现但当前运行环境/配置不满足。</li>
 * </ul>
 */
public enum BackendStatus {

    IMPLEMENTED("implemented"),
    RUNTIME_UNAVAILABLE("runtime_unavailable"),
    DECLARED_NOT_IMPLEMENTED("declared_not_implemented");

    private final String wire;

    BackendStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
