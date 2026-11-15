package com.bigdata.modules.telemetry.v1.routing;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.QueryMode;

import java.util.List;
import java.util.Set;

/**
 * 一个可参与选择的后端候选（已展开为具体实例 id）。
 *
 * <p>{@code code=OK} 表示配置层可发起调用；真正的可用性仍由后端 {@code evaluate} 判定。</p>
 */
public record BackendCandidate(String backendId,
                               BackendType backendType,
                               BackendStatus status,
                               CapabilityCode code,
                               String reason,
                               List<Requirement> requirements,
                               Set<QueryMode> queryModes,
                               String equivalenceGroup,
                               boolean authoritative,
                               int priority,
                               String notes) {

    public boolean selectable() {
        return status == BackendStatus.IMPLEMENTED && code == CapabilityCode.OK;
    }
}
