package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.routing.model.BackendCandidate;
import com.bigdata.modules.telemetry.service.routing.model.BackendSkip;

import java.util.List;

/**
 * 两个 v1 查询接口的统一响应外壳。
 *
 * <p>{@code outcome} 四态：ok / unavailable / not_implemented / failed；不可用与未实现时
 * {@code data} 恒为 null。{@code backend} 即使失败也返回，保留实际尝试与跳过原因。</p>
 */
public record TelemetryQueryResponse(String outcome,
                                       boolean retryable,
                                       Target target,
                                       String mode,
                                       BackendView backend,
                                       Object data,
                                       ErrorView error,
                                       MetaView meta) {

    public record BackendView(Selected selected,
                              String preferred,
                              boolean fallback,
                              List<BackendSkip> skipped,
                              List<Attempt> attempts,
                              List<BackendCandidate> candidates) {
    }

    public record Selected(String backendId, String backendType, String queryMode, boolean fallback) {
    }

    public record Attempt(String backendId, String backendType, CapabilityCode code,
                          long elapsedMs, boolean retryable) {
    }

    public record ErrorView(CapabilityCode code, String message, List<Requirement> requirements) {
    }

    public record MetaView(long elapsedMs, boolean partial, boolean degraded,
                           List<BackendWarning> warnings, Long retryAfterMs,
                           String collectedAt, String cursor, boolean truncated, ScanView scan) {
    }

    public record ScanView(int files, long bytes, long lines, int matched) {
    }

}
