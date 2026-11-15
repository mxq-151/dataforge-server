package com.bigdata.modules.telemetry.v1.backend.promql;

import com.bigdata.modules.telemetry.v1.model.BackendType;

import java.time.Duration;
import java.util.Map;

/**
 * Prometheus / VictoriaMetrics 的差异点：地址、路径前缀、认证、额外参数与限制。
 *
 * <p>公共子集（selector、query、query_range）完全共用，避免同一查询在两端语义漂移。</p>
 */
public record PromqlDialect(BackendType type,
                            String baseUrl,
                            String pathPrefix,
                            String authorizationHeader,
                            Map<String, String> extraParams,
                            Duration timeout,
                            int maxSeries,
                            int maxPointsPerSeries,
                            long maxResponseBytes,
                            String configurationError) {

    public boolean configured() {
        return configurationError == null || configurationError.isBlank();
    }

    public String normalizedPathPrefix() {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return "/api/v1";
        }
        String prefix = pathPrefix;
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    public String url(String apiPath) {
        String base = baseUrl == null ? "" : baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + normalizedPathPrefix() + apiPath;
    }
}
