package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/**
 * 后端对一次具体查询的能力判定结果。
 *
 * <p>判定阶段就应发现配置缺失、映射不兼容、查询模式不支持等问题，避免发起无效调用；
 * 不可用时必须带类型化 code 与补齐项。</p>
 */
public record BackendCapability(State state, CapabilityCode code, String reason,
                                List<Requirement> requirements) {

    public enum State {
        AVAILABLE,
        UNAVAILABLE,
        NOT_IMPLEMENTED
    }

    public BackendCapability {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static BackendCapability available() {
        return new BackendCapability(State.AVAILABLE, CapabilityCode.OK, "可用", List.of());
    }

    public static BackendCapability unavailable(CapabilityCode code, String reason,
                                                List<Requirement> requirements) {
        return new BackendCapability(State.UNAVAILABLE, code, reason, requirements);
    }

    public static BackendCapability notImplemented(String reason, List<Requirement> requirements) {
        return new BackendCapability(State.NOT_IMPLEMENTED,
                CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED, reason, requirements);
    }

    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }
}
