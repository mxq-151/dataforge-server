package com.bigdata.modules.telemetry.service.metricdata.discovery.impl;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.util.Strings;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderAuth;

/**
 * ClickHouse 指标名发现：system.metrics / system.events / system.asynchronous_metrics。
 *
 * <p>实测 24.8：三张表分别 300 / 99 / 785 行；{@code system.asynchronous_metrics}
 * 的列名是 {@code metric}，不是 {@code name}。发现与查询分离：这里只列名字，
 * ClickHouse 没有指标查询后端，metrics/query 仍返回 NOT_IMPLEMENTED。</p>
 */
@Component
public class ClickHouseMetricDiscoveryProvider implements MetricDiscoveryProvider {

    private static final String PROVIDER_ID = "clickhouse-discovery";
    private static final String SETTINGS_ID = "clickhouse-http";
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public ClickHouseMetricDiscoveryProvider(ProviderSettings settings,
                                             SecretResolver secretResolver,
                                             ObjectMapper objectMapper) {
        this.settings = settings;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerType() {
        return "clickhouse-http";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean enabled() {
        return settings.present(SETTINGS_ID) && settings.enabled(SETTINGS_ID);
    }

    @Override
    public boolean supports(Target target) {
        return "clickhouse".equals(target.serviceType());
    }

    @Override
    public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
        String baseUrl = settings.string(SETTINGS_ID, "base-url");
        if (baseUrl == null) {
            baseUrl = "http://127.0.0.1:8123";
        }
        EngineRestClient client = new EngineRestClient(baseUrl, authorizationHeader(),
                query.timeout(), MAX_RESPONSE_BYTES, objectMapper);

        List<DiscoveredMetric> metrics = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        BackendException firstFailure = null;
        for (Source source : sources()) {
            try {
                metrics.addAll(list(client, source, query.limit()));
            } catch (BackendException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        source.table() + ": " + Strings.safeMessage(e.getMessage(), "ClickHouse 指标名发现失败")));
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (metrics.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return new MetricDiscoveryResult(metrics, true, false, PROVIDER_ID,
                CapabilityCode.OK, null, List.of(), warnings);
    }

    private List<DiscoveredMetric> list(EngineRestClient client, Source source, int limit) {
        String sql = "SELECT " + source.nameColumn() + " AS name, '" + source.type()
                + "' AS type FROM " + source.table() + " FORMAT JSON";
        JsonNode root = client.getJson("/?query=" + encode(sql));
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "ClickHouse " + source.table() + " 响应缺少 data");
        }
        List<DiscoveredMetric> metrics = new ArrayList<>();
        for (JsonNode row : data) {
            if (metrics.size() >= limit) {
                break;
            }
            String name = row.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                metrics.add(new DiscoveredMetric(name, PROVIDER_ID,
                        "clickhouse:" + source.table(), row.path("type").asText(source.type()), null));
            }
        }
        return metrics;
    }

    private List<Source> sources() {
        return List.of(
                new Source("system.metrics", "name", "gauge"),
                new Source("system.events", "name", "counter"),
                new Source("system.asynchronous_metrics", "metric", "gauge"));
    }

    private String authorizationHeader() {
        return ProviderAuth.basic(settings, SETTINGS_ID, "default", secretResolver);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Source(String table, String nameColumn, String type) {
    }
}
