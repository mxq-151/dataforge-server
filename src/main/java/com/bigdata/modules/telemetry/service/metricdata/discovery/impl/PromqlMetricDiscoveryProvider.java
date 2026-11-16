package com.bigdata.modules.telemetry.service.metricdata.discovery.impl;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.config.ConfiguredBackendFactory;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.bigdata.modules.telemetry.config.properties.PromqlConfig;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * Prometheus / VictoriaMetrics 指标名发现：{@code GET /api/v1/label/__name__/values}。
 *
 * <p>实测 VictoriaMetrics 1.106 支持 {@code match[]={service_type="kafka"}} 按目标过滤；
 * 默认对 kafka / yarn / spark 启用（可通过 {@code discovery-service-types} 扩展）。</p>
 */
@Component
public class PromqlMetricDiscoveryProvider implements MetricDiscoveryProvider {

    private static final String PROVIDER_ID = "promql-discovery";

    private final TelemetryBackendProperties properties;
    private final ConfiguredBackendFactory backendFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public PromqlMetricDiscoveryProvider(TelemetryBackendProperties properties,
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
        return "promql";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean enabled() {
        return properties.getPrometheus().stream().anyMatch(this::configured)
                || properties.getVictoriametrics().stream().anyMatch(this::configured);
    }

    @Override
    public boolean supports(Target target) {
        return instances(BackendType.PROMETHEUS).stream()
                .anyMatch(instance -> supports(instance.config(), target.serviceType()))
                || instances(BackendType.VICTORIAMETRICS).stream()
                .anyMatch(instance -> supports(instance.config(), target.serviceType()));
    }

    @Override
    public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
        Set<DiscoveredMetric> metrics = new LinkedHashSet<>();
        List<BackendWarning> warnings = new ArrayList<>();
        boolean anySuccess = false;
        CapabilityCode failureCode = null;
        String failureReason = null;

        for (Instance instance : allInstances()) {
            if (!supports(instance.config(), query.target().serviceType())) {
                continue;
            }
            try {
                List<DiscoveredMetric> found = discover(instance, query, warnings);
                anySuccess = true;
                metrics.addAll(found);
            } catch (BackendException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        instance.providerId() + ": " + Strings.safeMessage(e.getMessage(), "指标名发现失败")));
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
                failureCode == null ? CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED : failureCode,
                failureReason == null ? "Prometheus / VictoriaMetrics 指标名发现未执行" : failureReason,
                List.of(new Requirement(RequirementKind.CONFIG,
                        "telemetry.backends.victoriametrics[].base-url",
                        "配置指标存储地址后才能按 service_type 做指标名发现")),
                warnings);
    }

    private List<DiscoveredMetric> discover(Instance instance, MetricDiscoveryQuery query,
                                            List<BackendWarning> warnings)
            throws BackendException {
        PromqlConfig config = instance.config();
        String selector = selector(query.target());
        String url = base(config) + prefix(config) + "/label/__name__/values?match[]="
                + Urls.encode(selector);
        JsonNode root = getJson(instance, url, query);
        if (!"success".equalsIgnoreCase(root.path("status").asText())) {
            throw new BackendException(CapabilityCode.UPSTREAM_ERROR,
                    "指标名发现返回 status=" + root.path("status").asText());
        }
        Map<String, MetricMetadata> metadata = fetchMetadata(instance, query, warnings);
        List<DiscoveredMetric> metrics = new ArrayList<>();
        String source = instance.providerId() + ":label/__name__/values";
        for (JsonNode name : root.path("data")) {
            if (metrics.size() >= query.limit()) {
                break;
            }
            if (name.isTextual() && !name.asText().isBlank()) {
                MetricMetadata meta = metadata.getOrDefault(name.asText(), MetricMetadata.EMPTY);
                metrics.add(new DiscoveredMetric(name.asText(), instance.providerId(), source,
                        meta.type(), meta.unit()));
            }
        }
        return metrics;
    }

    private JsonNode getJson(Instance instance, String url, MetricDiscoveryQuery query) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(query.timeout())
                .header("Accept", "application/json")
                .GET();
        String authorization = authorization(instance);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "指标名发现请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "指标名发现请求被中断");
        }
        if (response.statusCode() >= 400) {
            CapabilityCode code = response.statusCode() == 401 || response.statusCode() == 403
                    ? CapabilityCode.UPSTREAM_UNAUTHORIZED : CapabilityCode.UPSTREAM_ERROR;
            throw new BackendException(code,
                    "指标名发现失败，HTTP " + response.statusCode() + ": " + instance.providerId());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "指标名发现响应不是合法 JSON: " + instance.providerId());
        }
    }

    /**
     * 类型/单位来源：优先 /api/v1/metadata；VM 的 metadata 可能为空，此时回退到配置的
     * 抓取文本端点并解析 # TYPE / # HELP / # UNIT。
     */
    private Map<String, MetricMetadata> fetchMetadata(Instance instance, MetricDiscoveryQuery query,
                                                      List<BackendWarning> warnings) {
        Map<String, MetricMetadata> metadata = new LinkedHashMap<>();
        String url = base(instance.config()) + prefix(instance.config()) + "/metadata";
        try {
            JsonNode root = getJson(instance, url, query);
            JsonNode data = root.path("data");
            if (data.isObject()) {
                data.fields().forEachRemaining(entry -> {
                    JsonNode first = entry.getValue().isArray() && entry.getValue().size() > 0
                            ? entry.getValue().get(0) : entry.getValue();
                    metadata.put(entry.getKey(), new MetricMetadata(
                            text(first, "type"), text(first, "unit"), text(first, "help")));
                });
            }
        } catch (BackendException e) {
            warnings.add(BackendWarning.of("METRIC_METADATA_UNAVAILABLE",
                    instance.providerId() + " /api/v1/metadata 不可用: " + Strings.safeMessage(e.getMessage(), "指标名发现失败")));
        }
        if (metadata.isEmpty()) {
            boolean hasTextUrls = instance.config().getMetricMetadataUrls().stream()
                    .anyMatch(Strings::hasText);
            if (hasTextUrls) {
                warnings.add(BackendWarning.of("METRIC_METADATA_UNAVAILABLE",
                        instance.providerId() + " /api/v1/metadata 为空，回退抓取文本补类型"));
            }
            for (String metadataUrl : instance.config().getMetricMetadataUrls()) {
                if (!Strings.hasText(metadataUrl)) {
                    continue;
                }
                try {
                    parseTextMetadata(fetchText(metadataUrl, query), metadata);
                } catch (BackendException e) {
                    warnings.add(BackendWarning.of("METRIC_METADATA_UNAVAILABLE",
                            "抓取文本元数据不可用 " + metadataUrl + ": " + Strings.safeMessage(e.getMessage(), "指标名发现失败")));
                }
            }
        }
        return metadata;
    }

    private String fetchText(String url, MetricDiscoveryQuery query) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(query.timeout())
                .header("Accept", "text/plain")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new BackendException(CapabilityCode.UPSTREAM_ERROR,
                        "HTTP " + response.statusCode() + ": " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "抓取文本元数据被中断");
        }
    }

    private void parseTextMetadata(String body, Map<String, MetricMetadata> metadata) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> helps = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# TYPE ")) {
                String[] parts = trimmed.substring("# TYPE ".length()).split("\\s+", 2);
                if (parts.length == 2) {
                    types.put(parts[0], parts[1].trim());
                }
            } else if (trimmed.startsWith("# HELP ")) {
                String[] parts = trimmed.substring("# HELP ".length()).split("\\s+", 2);
                if (parts.length == 2) {
                    helps.put(parts[0], parts[1].trim());
                }
            } else if (trimmed.startsWith("# UNIT ")) {
                String[] parts = trimmed.substring("# UNIT ".length()).split("\\s+", 2);
                if (parts.length == 2) {
                    units.put(parts[0], parts[1].trim());
                }
            }
        }
        for (Map.Entry<String, String> entry : types.entrySet()) {
            metadata.putIfAbsent(entry.getKey(), new MetricMetadata(entry.getValue(),
                    units.get(entry.getKey()), helps.get(entry.getKey())));
        }
        for (Map.Entry<String, String> entry : helps.entrySet()) {
            metadata.putIfAbsent(entry.getKey(), new MetricMetadata(types.get(entry.getKey()),
                    units.get(entry.getKey()), entry.getValue()));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private record MetricMetadata(String type, String unit, String help) {
        private static final MetricMetadata EMPTY = new MetricMetadata(null, null, null);
    }

    private String authorization(Instance instance) {
        try {
            return backendFactory.resolveAuthorization(instance.config().getAuth(),
                    instance.type().wire() + "." + instance.id());
        } catch (IllegalStateException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    instance.providerId() + " 认证配置不可用: " + e.getMessage(),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.backends." + instance.type().wire() + "[].auth",
                            "配置可解析的指标存储凭据")));
        }
    }

    private List<Instance> allInstances() {
        List<Instance> instances = new ArrayList<>();
        instances.addAll(instances(BackendType.PROMETHEUS));
        instances.addAll(instances(BackendType.VICTORIAMETRICS));
        return instances;
    }

    private List<Instance> instances(BackendType type) {
        List<PromqlConfig> configs = type == BackendType.PROMETHEUS
                ? properties.getPrometheus() : properties.getVictoriametrics();
        List<Instance> result = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            PromqlConfig config = configs.get(i);
            String id = config.getId() == null || config.getId().isBlank()
                    ? type.wire() + "-" + (i + 1) : config.getId();
            result.add(new Instance(type, id, config));
        }
        return result;
    }

    private boolean configured(PromqlConfig config) {
        return config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    private boolean supports(PromqlConfig config, String serviceType) {
        if (!configured(config)) {
            return false;
        }
        List<String> types = config.getDiscoveryServiceTypes();
        return types != null && types.contains(serviceType);
    }

    private static String selector(Target target) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service_type", target.serviceType());
        if (target.componentId() != null && !target.componentId().isBlank()) {
            labels.put("component_id", target.componentId());
        }
        if (!target.clusterScoped()) {
            labels.put("host_id", target.hostId());
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append("=\"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
            first = false;
        }
        return builder.append('}').toString();
    }

    private static String base(PromqlConfig config) {
        String base = config.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String prefix(PromqlConfig config) {
        String prefix = config.getPathPrefix();
        if (prefix == null || prefix.isBlank()) {
            return "/api/v1";
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }


    private record Instance(BackendType type, String id, PromqlConfig config) {
        private String providerId() {
            return type.wire() + ":" + id;
        }
    }
}
