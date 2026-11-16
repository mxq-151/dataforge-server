package com.bigdata.modules.telemetry.service.metricdata.discovery.impl;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.config.ConfiguredBackendFactory;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * Flink 指标名发现：按 scope 调用真实 REST 端点。
 *
 * <p>实测：{@code GET /metrics} 不存在；可用的是
 * {@code /jobmanager/metrics}、{@code /taskmanagers/{id}/metrics}、
 * {@code /jobs/{jobId}/metrics}、{@code /jobs/{jobId}/vertices/{vid}/metrics}，
 * 不带 {@code get} 时返回 {@code [{"id":"..."}]}。</p>
 *
 * <p>默认只列 JM / TM 级；job 级需 {@code ref.jobId}，vertex 级需
 * {@code ref.jobId + ref.vertexId}，缺失时拒绝全量扫描。</p>
 */
@Component
public class FlinkMetricDiscoveryProvider implements MetricDiscoveryProvider {

    private static final String PROVIDER_ID = "flink-rest-discovery";
    private static final int MAX_TASK_MANAGERS = 20;

    private final TelemetryBackendProperties properties;
    private final ConfiguredBackendFactory backendFactory;
    private final ObjectMapper objectMapper;

    public FlinkMetricDiscoveryProvider(TelemetryBackendProperties properties,
                                        ConfiguredBackendFactory backendFactory,
                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.backendFactory = backendFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerType() {
        return "flink-rest";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return properties.getFlinkRest().stream()
                .anyMatch(config -> config.getBaseUrl() != null && !config.getBaseUrl().isBlank());
    }

    @Override
    public boolean supports(Target target) {
        return "flink".equals(target.serviceType())
                && ("flink.jobmanager".equals(target.componentId())
                || "flink.taskmanager".equals(target.componentId()));
    }

    @Override
    public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
        Set<DiscoveredMetric> metrics = new LinkedHashSet<>();
        List<BackendWarning> warnings = new ArrayList<>();
        boolean anySuccess = false;
        CapabilityCode failureCode = null;
        String failureReason = null;

        for (FlinkRestConfig config : properties.getFlinkRest()) {
            if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
                continue;
            }
            try {
                metrics.addAll(discover(config, query, warnings));
                anySuccess = true;
            } catch (BackendException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        providerId() + ": " + Strings.safeMessage(e.getMessage(), "Flink 指标名发现失败")));
                if (failureCode == null) {
                    failureCode = e.getCode();
                    failureReason = e.getMessage();
                }
            }
        }
        if (anySuccess) {
            return new MetricDiscoveryResult(new ArrayList<>(metrics), true, false,
                    PROVIDER_ID, CapabilityCode.OK, null, List.of(), warnings);
        }
        return MetricDiscoveryResult.failed(PROVIDER_ID,
                failureCode == null ? CapabilityCode.NOT_CONFIGURED : failureCode,
                failureReason == null ? "未配置 Flink REST 地址" : failureReason,
                List.of(new Requirement(RequirementKind.CONFIG,
                        "telemetry.backends.flink-rest[].base-url",
                        "配置 JobManager REST 地址后才能列举 Flink 指标名")),
                warnings);
    }

    private List<DiscoveredMetric> discover(FlinkRestConfig config, MetricDiscoveryQuery query,
                                            List<BackendWarning> warnings) {
        EngineRestClient client = new EngineRestClient(config.getBaseUrl(), authorization(config),
                timeout(config, query), config.getLimits().getMaxResponseBytes(), objectMapper);
        String componentId = query.target().componentId();
        String jobId = query.refs().get("jobId");
        String vertexId = query.refs().get("vertexId");
        List<DiscoveredMetric> metrics = new ArrayList<>();

        if ("flink.jobmanager".equals(componentId)) {
            metrics.addAll(list(client, "/jobmanager/metrics", "flink:jobmanager/metrics",
                    query.limit()));
            if (Strings.hasText(jobId)) {
                metrics.addAll(list(client, "/jobs/" + Urls.encode(jobId) + "/metrics",
                        "flink:jobs/" + jobId + "/metrics", query.limit()));
            }
        } else if ("flink.taskmanager".equals(componentId)) {
            List<String> taskManagers = taskManagers(client);
            for (String taskManager : taskManagers) {
                metrics.addAll(list(client, "/taskmanagers/" + Urls.encode(taskManager) + "/metrics",
                        "flink:taskmanagers/" + taskManager + "/metrics", query.limit()));
            }
            if (Strings.hasText(vertexId) && !Strings.hasText(jobId)) {
                throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                        "vertex 级指标发现需要同时提供 ref.jobId 与 ref.vertexId",
                        List.of(new Requirement(RequirementKind.TARGET, "ref.jobId",
                                "补充 Flink 作业 ID")));
            }
            if (Strings.hasText(jobId) && Strings.hasText(vertexId)) {
                metrics.addAll(list(client, "/jobs/" + Urls.encode(jobId) + "/vertices/"
                                + Urls.encode(vertexId) + "/metrics",
                        "flink:jobs/" + jobId + "/vertices/" + vertexId + "/metrics",
                        query.limit()));
            } else if (Strings.hasText(jobId)) {
                warnings.add(BackendWarning.of("VERTEX_REF_REQUIRED",
                        "已列出 TaskManager 级指标；vertex 级发现需要 ref.vertexId，已拒绝全量扫描"));
            }
        }
        return metrics;
    }

    private List<String> taskManagers(EngineRestClient client) {
        JsonNode root = client.getJson("/taskmanagers");
        List<String> ids = new ArrayList<>();
        for (JsonNode node : root.path("taskmanagers")) {
            if (ids.size() >= MAX_TASK_MANAGERS) {
                break;
            }
            String id = node.path("id").asText(null);
            if (Strings.hasText(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<DiscoveredMetric> list(EngineRestClient client, String path, String source,
                                        int limit) {
        JsonNode root = client.getJson(path);
        List<DiscoveredMetric> metrics = new ArrayList<>();
        for (JsonNode node : root) {
            if (metrics.size() >= limit) {
                break;
            }
            String id = node.path("id").asText(null);
            if (Strings.hasText(id)) {
                metrics.add(new DiscoveredMetric(id, PROVIDER_ID, source, null, null));
            }
        }
        return metrics;
    }

    private String authorization(FlinkRestConfig config) {
        try {
            return backendFactory.resolveAuthorization(config.getAuth(), "flink-rest." + config.getId());
        } catch (IllegalStateException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "Flink REST 认证配置不可用: " + e.getMessage(),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.backends.flink-rest[].auth", "配置可解析的 Flink REST 凭据")));
        }
    }

    private static Duration timeout(FlinkRestConfig config, MetricDiscoveryQuery query) {
        long configured = Math.max(config.getLimits().getTimeoutMs(), 1000);
        long requested = query.timeout().toMillis();
        return Duration.ofMillis(Math.min(configured, requested));
    }


}
