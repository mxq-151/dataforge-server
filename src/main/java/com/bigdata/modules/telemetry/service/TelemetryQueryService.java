package com.bigdata.modules.telemetry.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.dto.TelemetryQueryRequest;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.CapabilityState;
import com.bigdata.modules.telemetry.model.DataTypeDefinition;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.model.TelemetryIdentity;
import com.bigdata.modules.telemetry.model.TelemetryOutcome;
import com.bigdata.modules.telemetry.provider.CapabilityContext;
import com.bigdata.modules.telemetry.provider.TelemetryProvider;
import com.bigdata.modules.telemetry.provider.TelemetryProviderRegistry;
import com.bigdata.modules.telemetry.provider.TelemetryQueryContext;
import com.bigdata.modules.telemetry.provider.TelemetryQueryException;
import com.bigdata.modules.telemetry.vo.QueryError;
import com.bigdata.modules.telemetry.vo.QueryMeta;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一取数编排：能力判定 → provider 取数 → 结构化 outcome。
 *
 * <p>不可用/未实现不会调用上游，也不会返回 0 值或空数组；只有真正取数失败才标 FAILED。</p>
 */
@Service
public class TelemetryQueryService {

    private final TelemetryProviderRegistry providerRegistry;

    public TelemetryQueryService(TelemetryProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public TelemetryQueryResponse query(TelemetryQueryRequest request) {
        long start = System.currentTimeMillis();
        TelemetryIdentity identity = identityOf(request);
        validateTaxonomy(identity);
        DataTypeDefinition definition = Optional.ofNullable(DataTypes.get(identity.dataTypeId()))
                .orElseThrow(() -> new BusinessException(400, "未知数据类型: " + request.getDataTypeId()));

        if (!ServiceComponentCatalog.CLUSTER_HOST.equals(identity.hostId())) {
            return unavailable(identity, null, CapabilityCode.HOST_NOT_MANAGED,
                    "host 尚未纳管: " + identity.hostId(), start,
                    List.of(new Requirement(RequirementKind.API, "host.inventory",
                            "需要 host 纳管清单后才可按主机取数")));
        }
        if (!definition.implemented()) {
            return notImplemented(identity, definition, start);
        }

        TelemetryProvider provider = providerRegistry
                .resolve(identity.serviceType(), identity.componentId(), identity.dataTypeId())
                .orElse(null);
        if (provider == null) {
            return unavailable(identity, null, CapabilityCode.NO_PROVIDER,
                    "没有已注册的取数实现支持该四元组", start,
                    List.of(new Requirement(RequirementKind.PLUGIN,
                            "telemetry.providers[" + identity.dataTypeId() + "]",
                            "实现并注册 TelemetryProvider")));
        }

        Capability capability = provider.evaluate(new CapabilityContext(identity.serviceType(),
                identity.componentId(), identity.hostId(), identity.dataTypeId()));
        if (capability.state() != CapabilityState.AVAILABLE) {
            return unavailable(identity, provider.providerId(), capability.code(),
                    capability.reason(), start, capability.requirements());
        }

        try {
            Map<String, Object> data = provider.fetch(new TelemetryQueryContext(identity,
                    request.getRef(), request.getTimeRange(), request.getKeyword(),
                    request.getCursor(), request.getLimit(), request.getMaxBytes(),
                    request.getParams()));
            return ok(identity, provider.providerId(), data, start);
        } catch (TelemetryQueryException e) {
            return failed(identity, provider.providerId(), e.getCode(), e.getMessage(), start);
        } catch (RuntimeException e) {
            return failed(identity, provider.providerId(), CapabilityCode.UPSTREAM_ERROR,
                    Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()), start);
        }
    }

    private TelemetryIdentity identityOf(TelemetryQueryRequest request) {
        require(request.getServiceType(), "serviceType");
        require(request.getComponentId(), "componentId");
        require(request.getHostId(), "hostId");
        require(request.getDataTypeId(), "dataTypeId");
        return new TelemetryIdentity(request.getServiceType(), request.getComponentId(),
                request.getHostId(), request.getDataTypeId());
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

    private void validateTaxonomy(TelemetryIdentity identity) {
        if (!ServiceComponentCatalog.components().containsKey(identity.serviceType())) {
            throw new BusinessException(400, "未知服务类型: " + identity.serviceType());
        }
        if (!ServiceComponentCatalog.supports(identity.serviceType(), identity.componentId())) {
            throw new BusinessException(400, "组件不属于该服务: " + identity.componentId());
        }
    }

    private TelemetryQueryResponse ok(TelemetryIdentity identity, String providerId,
                                      Map<String, Object> data, long start) {
        Object cursor = data.get("nextCursor");
        boolean truncated = Boolean.TRUE.equals(data.get("truncated"));
        return new TelemetryQueryResponse(identity, TelemetryOutcome.OK, true, providerId,
                Instant.now().toString(), data, null,
                new QueryMeta(cursor == null ? null : cursor.toString(), truncated, false,
                        System.currentTimeMillis() - start));
    }

    private TelemetryQueryResponse unavailable(TelemetryIdentity identity, String providerId,
                                               CapabilityCode code, String reason, long start,
                                               List<Requirement> requirements) {
        return new TelemetryQueryResponse(identity, TelemetryOutcome.UNAVAILABLE,
                code.retryable(), providerId, null, null,
                new QueryError(code, reason, requirements),
                new QueryMeta(null, false, false, System.currentTimeMillis() - start));
    }

    private TelemetryQueryResponse notImplemented(TelemetryIdentity identity,
                                                  DataTypeDefinition definition, long start) {
        List<Requirement> requirements = definition.dataTypeId().startsWith("os.")
                ? List.of(new Requirement(RequirementKind.API, "host.metrics",
                "需要主机指标采集接口（node_exporter / JMX 桥 / agent 任一）"))
                : List.of(new Requirement(RequirementKind.CONFIG,
                "logs.sources[*].kinds=slow_query", "需要慢查询日志采集与解析实现"));
        return new TelemetryQueryResponse(identity, TelemetryOutcome.NOT_IMPLEMENTED, false,
                null, null, null,
                new QueryError(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        definition.label() + " 已声明但尚未实现；不会返回 0 值或空数组", requirements),
                new QueryMeta(null, false, false, System.currentTimeMillis() - start));
    }

    private TelemetryQueryResponse failed(TelemetryIdentity identity, String providerId,
                                          CapabilityCode code, String reason, long start) {
        return new TelemetryQueryResponse(identity, TelemetryOutcome.FAILED, code.retryable(),
                providerId, null, null,
                new QueryError(code, reason, List.of()),
                new QueryMeta(null, false, false, System.currentTimeMillis() - start));
    }
}
