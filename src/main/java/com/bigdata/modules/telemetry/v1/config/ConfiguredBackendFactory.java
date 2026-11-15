package com.bigdata.modules.telemetry.v1.config;

import com.bigdata.modules.telemetry.v1.backend.doris.DorisAuditLogBackend;
import com.bigdata.modules.telemetry.v1.backend.ds.DsApiLogBackend;
import com.bigdata.modules.telemetry.v1.backend.es.ElasticsearchLogBackend;
import com.bigdata.modules.telemetry.v1.backend.flink.FlinkRestMetricBackend;
import com.bigdata.modules.telemetry.v1.backend.localdisk.LocalDiskLogBackend;
import com.bigdata.modules.telemetry.v1.backend.promql.PromqlDialect;
import com.bigdata.modules.telemetry.v1.backend.promql.PromqlMetricBackend;
import com.bigdata.modules.telemetry.v1.backend.spark.SparkRestMetricBackend;
import com.bigdata.modules.telemetry.v1.backend.yarn.YarnLogBackend;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.Auth;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.DsConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.DorisAuditConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.EsConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.LocalDiskConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.PromqlConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.YarnConfig;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQueryResult;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.ExceptionSummaryBackend;
import com.bigdata.modules.telemetry.v1.spi.LogBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按配置实例化存储后端。
 *
 * <p>只创建「配置里出现」的后端；未配置的实例不会被创建，查询由路由层返回 NOT_CONFIGURED。
 * 认证解析失败不会静默使用空凭据，而是把错误带入能力判定。</p>
 */
@Component
public class ConfiguredBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredBackendFactory.class);

    private final List<MetricBackend> metricBackends = new ArrayList<>();
    private final List<LogBackend> logBackends = new ArrayList<>();
    private final SecretResolver secretResolver;

    public ConfiguredBackendFactory(TelemetryBackendProperties properties, ObjectMapper objectMapper,
                                    SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
        buildPromql(properties.getPrometheus(), BackendType.PROMETHEUS, "prometheus", objectMapper);
        buildPromql(properties.getVictoriametrics(), BackendType.VICTORIAMETRICS, "victoriametrics",
                objectMapper);
        for (LocalDiskConfig config : properties.getLocalDisk()) {
            String id = isBlank(config.getId()) ? "local-disk" : config.getId();
            logBackends.add(new LocalDiskLogBackend(id, config, objectMapper));
        }
        for (EsConfig config : properties.getElasticsearch()) {
            String id = isBlank(config.getId()) ? "es-logs" : config.getId();
            String header = null;
            String error = null;
            try {
                header = authorization(config.getAuth(), "elasticsearch." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Elasticsearch 后端 {} 认证配置不可用: {}", id, error);
            }
            ElasticsearchLogBackend backend = new ElasticsearchLogBackend(id, config, header, objectMapper);
            logBackends.add(new AuthAwareLogBackend(backend, error));
        }
        for (FlinkRestConfig config : properties.getFlinkRest()) {
            String id = isBlank(config.getId()) ? "flink-rest" : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), "flink-rest." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Flink REST 后端 {} 认证配置不可用: {}", id, error);
            }
            MetricBackend backend = new FlinkRestMetricBackend(id, config, header, objectMapper);
            metricBackends.add(error == null ? backend : new AuthAwareMetricBackend(backend, error));
        }
        for (SparkRestConfig config : properties.getSparkRest()) {
            String id = isBlank(config.getId()) ? "spark-rest" : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), "spark-rest." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Spark REST 后端 {} 认证配置不可用: {}", id, error);
            }
            MetricBackend backend = new SparkRestMetricBackend(id, config, header, objectMapper);
            metricBackends.add(error == null ? backend : new AuthAwareMetricBackend(backend, error));
        }
        for (YarnConfig config : properties.getYarn()) {
            String id = isBlank(config.getId()) ? "yarn" : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), "yarn." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("YARN 日志后端 {} 认证配置不可用: {}", id, error);
            }
            LogBackend backend = new YarnLogBackend(id, config, header, objectMapper);
            logBackends.add(error == null ? backend : new AuthAwareLogBackend(backend, error));
        }
        for (DsConfig config : properties.getDsApi()) {
            String id = isBlank(config.getId()) ? "ds-api" : config.getId();
            String username = null;
            String password = null;
            String error = null;
            try {
                username = secretResolver.resolve(config.getAuth().getUsername(),
                        config.getAuth().getUsernameRef(), "ds-api." + id + ".username");
                password = secretResolver.resolve(config.getAuth().getPassword(),
                        config.getAuth().getPasswordRef(), "ds-api." + id + ".password");
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("DS 日志后端 {} 凭据配置不可用: {}", id, error);
            }
            LogBackend backend = new DsApiLogBackend(id, config, username, password, objectMapper);
            logBackends.add(error == null ? backend : new AuthAwareLogBackend(backend, error));
        }
        for (DorisAuditConfig config : properties.getDorisAudit()) {
            String id = isBlank(config.getId()) ? "doris-audit" : config.getId();
            String error = null;
            try {
                config.setUsername(secretResolver.resolve(config.getUsername(),
                        config.getUsernameRef(), "doris-audit." + id + ".username"));
                config.setPassword(secretResolver.resolve(config.getPassword(),
                        config.getPasswordRef(), "doris-audit." + id + ".password"));
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Doris 审计后端 {} 凭据配置不可用: {}", id, error);
            }
            LogBackend backend = new DorisAuditLogBackend(id, config);
            logBackends.add(error == null ? backend : new AuthAwareLogBackend(backend, error));
        }
    }

    public List<MetricBackend> metricBackends() {
        return List.copyOf(metricBackends);
    }

    public List<LogBackend> logBackends() {
        return List.copyOf(logBackends);
    }

    private void buildPromql(List<PromqlConfig> configs, BackendType type, String prefix,
                             ObjectMapper objectMapper) {
        for (int i = 0; i < configs.size(); i++) {
            PromqlConfig config = configs.get(i);
            String id = isBlank(config.getId()) ? prefix + "-" + (i + 1) : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), prefix + "." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
            }
            if (isBlank(config.getBaseUrl()) && error == null) {
                error = "未配置 telemetry.backends." + prefix + "[].base-url";
            }
            Map<String, String> extraParams = new LinkedHashMap<>();
            if (type == BackendType.VICTORIAMETRICS) {
                extraParams.put("partial_response", String.valueOf(config.getQuery().isPartialResponse()));
                extraParams.put("max_points_per_timeseries",
                        String.valueOf(config.getQuery().getMaxPointsPerTimeseries()));
            }
            PromqlDialect dialect = new PromqlDialect(type,
                    config.getBaseUrl(),
                    config.getPathPrefix(),
                    header,
                    extraParams,
                    Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000)),
                    config.getLimits().getMaxSeries(),
                    config.getLimits().getMaxPointsPerSeries(),
                    config.getLimits().getMaxResponseBytes(),
                    error);
            metricBackends.add(new PromqlMetricBackend(id, dialect, objectMapper));
        }
    }

    /** 供运行时引擎等其它后端复用同一套凭据解析规则。 */
    public String resolveAuthorization(Auth auth, String owner) {
        return authorization(auth, owner);
    }

    private String authorization(Auth auth, String owner) {
        if (auth == null || isBlank(auth.getType()) || "none".equalsIgnoreCase(auth.getType())) {
            return null;
        }
        if ("basic".equalsIgnoreCase(auth.getType())) {
            String username = secretResolver.resolve(auth.getUsername(), auth.getUsernameRef(),
                    owner + ".username");
            String password = secretResolver.resolve(auth.getPassword(), auth.getPasswordRef(),
                    owner + ".password");
            return "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        if ("bearer".equalsIgnoreCase(auth.getType())) {
            return "Bearer " + secretResolver.resolve(auth.getToken(), auth.getTokenRef(),
                    owner + ".token");
        }
        throw new IllegalStateException("未知认证类型: " + auth.getType());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 认证配置失败时，让能力判定直接返回 NOT_CONFIGURED，而不是带着空凭据去撞 401。 */
    private record AuthAwareMetricBackend(MetricBackend delegate, String configurationError)
            implements MetricBackend, ExceptionSummaryBackend {

        @Override
        public String backendId() {
            return delegate.backendId();
        }

        @Override
        public BackendType backendType() {
            return delegate.backendType();
        }

        @Override
        public BackendCapability evaluate(MetricQuerySpec spec) {
            if (configurationError != null) {
                return BackendCapability.unavailable(
                        com.bigdata.modules.telemetry.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            return delegate.evaluate(spec);
        }

        @Override
        public MetricQueryResult fetch(MetricQuerySpec spec) {
            return delegate.fetch(spec);
        }

        @Override
        public BackendCapability evaluate(ExceptionQuerySpec spec) {
            if (configurationError != null) {
                return BackendCapability.unavailable(
                        com.bigdata.modules.telemetry.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            if (!(delegate instanceof ExceptionSummaryBackend backend)) {
                return BackendCapability.notImplemented(
                        "后端 " + delegate.backendId() + " 不提供异常摘要", List.of());
            }
            return backend.evaluate(spec);
        }

        @Override
        public ExceptionQueryResult fetch(ExceptionQuerySpec spec) {
            return summary().fetch(spec);
        }

        private ExceptionSummaryBackend summary() {
            if (delegate instanceof ExceptionSummaryBackend backend) {
                return backend;
            }
            throw new com.bigdata.modules.telemetry.v1.spi.BackendException(
                    com.bigdata.modules.telemetry.model.CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                    "后端 " + delegate.backendId() + " 不提供异常摘要");
        }
    }

    /** 认证配置失败时，让能力判定直接返回 NOT_CONFIGURED，而不是带着空凭据去撞 401。 */
    private record AuthAwareLogBackend(LogBackend delegate, String configurationError) implements LogBackend {

        @Override
        public String backendId() {
            return delegate.backendId();
        }

        @Override
        public BackendType backendType() {
            return delegate.backendType();
        }

        @Override
        public com.bigdata.modules.telemetry.v1.spi.BackendCapability evaluate(
                com.bigdata.modules.telemetry.v1.spi.LogQuerySpec spec) {
            if (configurationError != null) {
                return com.bigdata.modules.telemetry.v1.spi.BackendCapability.unavailable(
                        com.bigdata.modules.telemetry.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            return delegate.evaluate(spec);
        }

        @Override
        public com.bigdata.modules.telemetry.v1.spi.LogQueryResult fetch(
                com.bigdata.modules.telemetry.v1.spi.LogQuerySpec spec) {
            return delegate.fetch(spec);
        }
    }
}
