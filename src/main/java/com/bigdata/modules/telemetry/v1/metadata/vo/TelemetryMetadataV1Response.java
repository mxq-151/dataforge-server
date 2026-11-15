package com.bigdata.modules.telemetry.v1.metadata.vo;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataData;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataTarget;
import com.bigdata.modules.telemetry.v1.metadata.model.TargetAttempt;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;

import java.util.List;

/**
 * 服务版本与配置查询的响应外壳，与指标 / 日志 / 运行时共用四态约定。
 *
 * <p>{@code outcome}：ok / unavailable / not_implemented / failed；不可用与未实现时
 * {@code data} 恒为 null。批量查询部分失败用 {@code ok + meta.partial=true}。</p>
 */
public record TelemetryMetadataV1Response(String outcome,
                                          boolean retryable,
                                          List<MetadataTarget> targets,
                                          String mode,
                                          ProviderView providers,
                                          MetadataData data,
                                          ErrorView error,
                                          MetaView meta) {

    /** provider 选择与尝试记录；即使失败也返回，保留实际尝试与跳过原因。 */
    public record ProviderView(List<Selected> selected,
                               List<TargetAttempt> attempts,
                               List<Skip> skipped) {
    }

    /** 单个 target 最终生效的 provider。 */
    public record Selected(MetadataTarget target, String providerId, String providerType,
                           int order) {
    }

    public record Skip(MetadataTarget target, String providerId, String reason) {
    }

    public record ErrorView(CapabilityCode code, String message, List<Requirement> requirements) {
    }

    public record MetaView(long elapsedMs, boolean partial, boolean degraded,
                           List<BackendWarning> warnings, String collectedAt,
                           boolean truncated) {
    }
}
