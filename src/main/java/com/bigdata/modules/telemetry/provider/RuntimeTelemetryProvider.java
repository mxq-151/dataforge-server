package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.runtime.dto.RuntimeQueryDTO;
import com.bigdata.modules.runtime.model.RuntimeSource;
import com.bigdata.modules.runtime.service.RuntimeAccessControlService;
import com.bigdata.modules.runtime.service.RuntimeQueryService;
import com.bigdata.modules.runtime.service.RuntimeSourceCatalog;
import com.bigdata.modules.runtime.vo.RuntimeComponentVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 指标类数据类型的取数实现，桥接现有 RuntimeSourceCatalog / RuntimeQueryService。
 *
 * <p>Phase 1 的判定粒度是服务级：同一服务下组件的可用性共享 RuntimeSource 的配置状态。</p>
 */
@Component
public class RuntimeTelemetryProvider implements TelemetryProvider {

    private static final Map<String, List<String>> COMPONENTS = Map.of(
            "spark", List.of("spark.driver", "spark.executor"),
            "flink", List.of("flink.jobmanager", "flink.taskmanager"));

    /** JVM 指标目前只在 TaskManager / Executor 维度采集。 */
    private static final Set<String> JVM_COMPONENTS = Set.of("flink.taskmanager", "spark.executor");

    private static final Set<String> DATA_TYPES = Set.of(
            DataTypes.APP_METRICS, DataTypes.JVM_RUNTIME, DataTypes.JVM_GC);

    private static final Map<String, List<String>> TRANSPORTS = Map.of(
            "spark", List.of("spark-shs"),
            "flink", List.of("flink-rest"));

    private final RuntimeSourceCatalog sourceCatalog;
    private final RuntimeAccessControlService accessControl;
    private final RuntimeQueryService runtimeQueryService;

    public RuntimeTelemetryProvider(RuntimeSourceCatalog sourceCatalog,
                                    RuntimeAccessControlService accessControl,
                                    RuntimeQueryService runtimeQueryService) {
        this.sourceCatalog = sourceCatalog;
        this.accessControl = accessControl;
        this.runtimeQueryService = runtimeQueryService;
    }

    @Override
    public String providerId() {
        return "runtime-adapter";
    }

    @Override
    public boolean supports(String serviceType, String componentId, String dataTypeId) {
        if (!DATA_TYPES.contains(dataTypeId)) {
            return false;
        }
        if (!COMPONENTS.getOrDefault(serviceType, List.of()).contains(componentId)) {
            return false;
        }
        if (DataTypes.APP_METRICS.equals(dataTypeId)) {
            return true;
        }
        return JVM_COMPONENTS.contains(componentId);
    }

    @Override
    public Capability evaluate(CapabilityContext context) {
        String serviceType = context.serviceType();
        String componentId = context.componentId();
        String dataTypeId = context.dataTypeId();
        List<String> transports = TRANSPORTS.getOrDefault(serviceType, List.of());

        if (!DataTypes.APP_METRICS.equals(dataTypeId) && !JVM_COMPONENTS.contains(componentId)) {
            return Capability.unavailable(dataTypeId, CapabilityCode.NO_PROVIDER,
                    "该组件没有 " + dataTypeId + " 采集实现", providerId(), transports,
                    List.of(new Requirement(RequirementKind.PLUGIN,
                            "telemetry.providers[" + dataTypeId + "]",
                            "为该组件注册采集实现")));
        }

        List<RuntimeSource> matching = sourceCatalog.listAll().stream()
                .filter(source -> source.getServiceType() != null
                        && serviceType.equals(source.getServiceType().wireName()))
                .toList();
        if (matching.isEmpty()) {
            return unavailable(dataTypeId, CapabilityCode.NOT_CONFIGURED,
                    "未配置 " + serviceType + " 的运行指标源", providerId(), transports,
                    new Requirement(RequirementKind.CONFIG,
                            "runtime.sources[" + serviceType + "]",
                            "新增 base-url 指向 " + serviceType + " REST/History Server 的指标源"));
        }

        List<RuntimeSource> readable = matching.stream().filter(accessControl::canRead).toList();
        if (readable.isEmpty()) {
            return unavailable(dataTypeId, CapabilityCode.PERMISSION_DENIED,
                    "当前用户无权读取该指标源", providerId(), transports,
                    new Requirement(RequirementKind.PERMISSION,
                            "runtime.sources[" + matching.get(0).getId() + "].rbac",
                            "为当前角色授予该指标源读权限"));
        }

        List<RuntimeSource> enabled = readable.stream().filter(RuntimeSource::isEnabled).toList();
        if (enabled.isEmpty()) {
            RuntimeSource disabled = readable.get(0);
            return unavailable(dataTypeId, CapabilityCode.SOURCE_DISABLED,
                    Optional.ofNullable(disabled.getDisabledReason()).orElse("指标源已禁用"),
                    providerId(), transports,
                    new Requirement(RequirementKind.CONFIG,
                            "runtime.sources[" + disabled.getId() + "].enabled",
                            "启用该指标源"));
        }

        RuntimeSource ready = enabled.stream()
                .filter(source -> source.getBaseUrl() != null && !source.getBaseUrl().isBlank())
                .findFirst()
                .orElse(null);
        if (ready == null) {
            return unavailable(dataTypeId, CapabilityCode.NOT_CONFIGURED,
                    "指标源缺少 base-url", providerId(), transports,
                    new Requirement(RequirementKind.CONFIG,
                            "runtime.sources[" + enabled.get(0).getId() + "].base-url",
                            "配置该引擎的 REST / History Server 地址"));
        }
        return Capability.available(dataTypeId, providerId(), transports);
    }

    @Override
    public Map<String, Object> fetch(TelemetryQueryContext context) {
        String dataTypeId = context.identity().dataTypeId();
        RuntimeQueryDTO dto = new RuntimeQueryDTO();
        dto.setServiceType(context.identity().serviceType());
        dto.setCluster(context.ref() == null ? "default"
                : context.ref().getOrDefault("cluster", "default"));
        dto.setJobId(firstNonBlank(context.ref(), "jobId", "applicationId", "appId"));
        dto.setIncludeComponents(Boolean.TRUE);
        if (DataTypes.JVM_GC.equals(dataTypeId) || DataTypes.JVM_RUNTIME.equals(dataTypeId)) {
            dto.setFamilies(List.of("jvm_gc"));
        }
        String role = roleOf(context.identity().componentId());
        if (role != null) {
            dto.setComponentRole(role);
        }
        String engineComponent = context.ref() == null ? null : context.ref().get("componentId");
        if (engineComponent != null && !engineComponent.isBlank()) {
            dto.setComponents(List.of(engineComponent));
        }

        RuntimeSnapshotVO snapshot = runtimeQueryService.snapshot(dto);
        if (snapshot.getErrorCode() != null) {
            throw new TelemetryQueryException(mapErrorCode(snapshot.getErrorCode()),
                    Optional.ofNullable(snapshot.getDegradationReason())
                            .orElse("运行指标查询失败"));
        }

        Predicate<RuntimeMetricVO> filter = metricFilter(dataTypeId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capturedAt", snapshot.getCapturedAt());
        data.put("state", snapshot.getState());
        data.put("jobMetrics", snapshot.getJobMetrics().stream().filter(filter).toList());
        data.put("components", snapshot.getComponents().stream()
                .map(component -> componentSlice(component, filter))
                .toList());
        if (DataTypes.APP_METRICS.equals(dataTypeId)) {
            data.put("checkpoint", snapshot.getCheckpoint());
            data.put("unavailableFamilies", snapshot.getUnavailableFamilies());
            data.put("warnings", snapshot.getWarnings());
        }
        return data;
    }

    private Map<String, Object> componentSlice(RuntimeComponentVO component,
                                               Predicate<RuntimeMetricVO> filter) {
        Map<String, Object> slice = new LinkedHashMap<>();
        slice.put("id", component.getId());
        slice.put("name", component.getName());
        slice.put("type", component.getType());
        slice.put("host", component.getHost());
        slice.put("status", component.getStatus());
        slice.put("metrics", component.getMetrics().stream().filter(filter).toList());
        return slice;
    }

    /**
     * jvm.runtime / jvm.gc 是同一批 JVM 指标的两个切片；
     * app.metrics 返回完整快照。
     */
    private Predicate<RuntimeMetricVO> metricFilter(String dataTypeId) {
        if (DataTypes.APP_METRICS.equals(dataTypeId)) {
            return metric -> true;
        }
        if (DataTypes.JVM_GC.equals(dataTypeId)) {
            return metric -> contains(metric, "gc");
        }
        return metric -> !contains(metric, "gc")
                && (contains(metric, "heap") || contains(metric, "nonheap")
                || contains(metric, "memory") || contains(metric, "threads")
                || contains(metric, "cores") || contains(metric, "activetasks"));
    }

    private boolean contains(RuntimeMetricVO metric, String keyword) {
        return metric.getName() != null && metric.getName().toLowerCase().contains(keyword);
    }

    private String roleOf(String componentId) {
        return switch (componentId) {
            case "spark.executor" -> "executor";
            case "flink.taskmanager" -> "task_manager";
            default -> null;
        };
    }

    private String firstNonBlank(Map<String, String> ref, String... keys) {
        if (ref == null) {
            return null;
        }
        for (String key : keys) {
            String value = ref.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private CapabilityCode mapErrorCode(String errorCode) {
        String code = errorCode == null ? "" : errorCode.toUpperCase();
        if (code.contains("TIMEOUT")) {
            return CapabilityCode.UPSTREAM_TIMEOUT;
        }
        if (code.contains("UNAUTHORIZED") || code.contains("AUTH")) {
            return CapabilityCode.UPSTREAM_UNAUTHORIZED;
        }
        if (code.contains("CONFIG") || code.contains("UNSUPPORTED")
                || code.contains("NOT_FOUND")) {
            return CapabilityCode.NOT_CONFIGURED;
        }
        return CapabilityCode.UPSTREAM_ERROR;
    }

    private Capability unavailable(String dataTypeId,
                                   CapabilityCode code,
                                   String reason,
                                   String providerId,
                                   List<String> transports,
                                   Requirement requirement) {
        return Capability.unavailable(dataTypeId, code, reason, providerId, transports,
                List.of(requirement));
    }
}
