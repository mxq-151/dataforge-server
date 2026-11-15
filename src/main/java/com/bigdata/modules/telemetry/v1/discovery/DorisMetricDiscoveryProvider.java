package com.bigdata.modules.telemetry.v1.discovery;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris 指标名发现：FE / BE 的 {@code GET /metrics}（Prometheus 文本）。
 *
 * <p>实测 4.1.5：FE 8030 有 155 个 HELP 名，BE 8040 有 228 个 TYPE 名；
 * Doris 没有 {@code SHOW METRICS} / {@code information_schema} 指标表，
 * {@code SHOW PROC '/metrics'} 也不存在。发现与查询分离：只列名字，
 * Doris 没有指标查询后端，metrics/query 仍返回 NOT_IMPLEMENTED。</p>
 */
@Component
public class DorisMetricDiscoveryProvider implements MetricDiscoveryProvider {

    private static final String PROVIDER_ID = "doris-discovery";
    private static final String SETTINGS_ID = "doris-fe";
    private static final Pattern SAMPLE_NAME = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)");

    private final ProviderSettings settings;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3)).build();

    public DorisMetricDiscoveryProvider(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerType() {
        return "doris-http";
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
        return "doris".equals(target.serviceType())
                && ("doris.fe".equals(target.componentId()) || "doris.be".equals(target.componentId()));
    }

    @Override
    public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
        List<Source> sources = sources(query.target().componentId());
        if (sources.isEmpty()) {
            return MetricDiscoveryResult.failed(PROVIDER_ID, CapabilityCode.NOT_CONFIGURED,
                    "未配置 Doris FE/BE metrics 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.metadata.providers." + SETTINGS_ID + ".fe-metrics-url",
                            "配置如 http://127.0.0.1:8030/metrics"),
                            new Requirement(RequirementKind.CONFIG,
                                    "telemetry.metadata.providers." + SETTINGS_ID + ".be-metrics-url",
                                    "配置如 http://127.0.0.1:8040/metrics")),
                    List.of());
        }

        Map<String, DiscoveredMetric> metrics = new LinkedHashMap<>();
        List<BackendWarning> warnings = new ArrayList<>();
        boolean anySuccess = false;
        BackendException firstFailure = null;
        for (Source source : sources) {
            try {
                parse(fetch(source.url(), query), source, metrics, query.limit());
                anySuccess = true;
            } catch (BackendException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        source.id() + ": " + safe(e.getMessage())));
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (!anySuccess && firstFailure != null) {
            throw firstFailure;
        }
        return new MetricDiscoveryResult(new ArrayList<>(metrics.values()), true, false,
                PROVIDER_ID, CapabilityCode.OK, null, List.of(), warnings);
    }

    private List<Source> sources(String componentId) {
        List<Source> sources = new ArrayList<>();
        String fe = settings.string(SETTINGS_ID, "fe-metrics-url");
        String be = settings.string(SETTINGS_ID, "be-metrics-url");
        if ("doris.fe".equals(componentId) && hasText(fe)) {
            sources.add(new Source("doris.fe", fe, "doris:fe/metrics"));
        }
        if ("doris.be".equals(componentId) && hasText(be)) {
            sources.add(new Source("doris.be", be, "doris:be/metrics"));
        }
        return sources;
    }

    private String fetch(String url, MetricDiscoveryQuery query) {
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
                CapabilityCode code = response.statusCode() == 401 || response.statusCode() == 403
                        ? CapabilityCode.UPSTREAM_UNAUTHORIZED : CapabilityCode.UPSTREAM_ERROR;
                throw new BackendException(code,
                        "Doris metrics 请求失败，HTTP " + response.statusCode() + ": " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "Doris metrics 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "Doris metrics 请求被中断");
        }
    }

    private void parse(String body, Source source, Map<String, DiscoveredMetric> metrics, int limit) {
        for (String line : body.split("\\R")) {
            if (metrics.size() >= limit) {
                return;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("# TYPE ")) {
                String[] parts = trimmed.substring("# TYPE ".length()).split("\\s+");
                if (parts.length >= 2) {
                    add(metrics, parts[0], parts[1], source);
                }
            } else if (trimmed.startsWith("# HELP ")) {
                String[] parts = trimmed.substring("# HELP ".length()).split("\\s+");
                if (parts.length >= 1) {
                    add(metrics, parts[0], null, source);
                }
            } else if (!trimmed.startsWith("#")) {
                Matcher matcher = SAMPLE_NAME.matcher(trimmed);
                if (matcher.find()) {
                    add(metrics, matcher.group(1), null, source);
                }
            }
        }
    }

    private void add(Map<String, DiscoveredMetric> metrics, String name, String type,
                     Source source) {
        if (!hasText(name) || metrics.containsKey(name)) {
            return;
        }
        metrics.put(name, new DiscoveredMetric(name, PROVIDER_ID, source.id(), type, null));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "Doris 指标名发现失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record Source(String id, String url, String label) {
    }
}
