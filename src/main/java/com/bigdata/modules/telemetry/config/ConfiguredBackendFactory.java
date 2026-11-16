package com.bigdata.modules.telemetry.config;

import com.bigdata.modules.telemetry.service.logdata.server.DorisAuditLogBackend;
import com.bigdata.modules.telemetry.service.logdata.task.DsApiLogBackend;
import com.bigdata.modules.telemetry.service.logdata.server.ElasticsearchLogBackend;
import com.bigdata.modules.telemetry.service.logdata.task.FlinkRestExceptionBackend;
import com.bigdata.modules.telemetry.service.metricdata.task.FlinkRestMetricBackend;
import com.bigdata.modules.telemetry.service.logdata.server.LocalDiskLogBackend;
import com.bigdata.modules.telemetry.service.metricdata.server.PromqlDialect;
import com.bigdata.modules.telemetry.service.metricdata.server.PromqlMetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.task.SparkShsRestMetricBackend;
import com.bigdata.modules.telemetry.service.logdata.task.YarnLogBackend;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.spi.ExceptionSummaryBackend;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.config.properties.Auth;
import com.bigdata.modules.telemetry.config.properties.PromqlConfig;
import com.bigdata.modules.telemetry.config.properties.LocalDiskConfig;
import com.bigdata.modules.telemetry.config.properties.EsConfig;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.config.properties.SparkShsRestConfig;
import com.bigdata.modules.telemetry.config.properties.YarnConfig;
import com.bigdata.modules.telemetry.config.properties.DsConfig;
import com.bigdata.modules.telemetry.config.properties.DorisAuditConfig;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.bigdata.modules.telemetry.util.Strings;
import com.bigdata.modules.telemetry.common.http.AuthorizationHeader;

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
    private final List<ExceptionSummaryBackend> exceptionBackends = new ArrayList<>();
    private final SecretResolver secretResolver;

    public ConfiguredBackendFactory(TelemetryBackendProperties properties, ObjectMapper objectMapper,
                                    SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
        buildPromql(properties.getPrometheus(), BackendType.PROMETHEUS, "prometheus", objectMapper);
        buildPromql(properties.getVictoriametrics(), BackendType.VICTORIAMETRICS, "victoriametrics",
                objectMapper);
        for (LocalDiskConfig config : properties.getLocalDisk()) {
            String id = Strings.isBlank(config.getId()) ? "local-disk" : config.getId();
            logBackends.add(new LocalDiskLogBackend(id, config, objectMapper));
        }
        for (EsConfig config : properties.getElasticsearch()) {
            String id = Strings.isBlank(config.getId()) ? "es-logs" : config.getId();
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
            String id = Strings.isBlank(config.getId()) ? "flink-rest" : config.getId();
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
            ExceptionSummaryBackend exceptionBackend =
                    new FlinkRestExceptionBackend(id, config, header, objectMapper);
            exceptionBackends.add(error == null
                    ? exceptionBackend : new AuthAwareExceptionBackend(exceptionBackend, error));
        }
        for (SparkShsRestConfig config : properties.getSparkShsRest()) {
            String id = Strings.isBlank(config.getId()) ? "spark-shs-rest" : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), "spark-shs-rest." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Spark SHS REST 后端 {} 认证配置不可用: {}", id, error);
            }
            MetricBackend backend = new SparkShsRestMetricBackend(id, config, header, objectMapper);
            metricBackends.add(error == null ? backend : new AuthAwareMetricBackend(backend, error));
        }
        for (YarnConfig config : properties.getYarn()) {
            String id = Strings.isBlank(config.getId()) ? "yarn" : config.getId();
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
            String id = Strings.isBlank(config.getId()) ? "ds-api" : config.getId();
            String token = null;
            String error = null;
            try {
                token = secretResolver.resolve(config.getToken(),
                        config.getTokenRef(), "ds-api." + id + ".token");
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("DS 日志后端 {} 令牌配置不可用: {}", id, error);
            }
            LogBackend backend = new DsApiLogBackend(id, config, token, objectMapper);
            logBackends.add(error == null ? backend : new AuthAwareLogBackend(backend, error));
        }
        for (DorisAuditConfig config : properties.getDorisAudit()) {
            String id = Strings.isBlank(config.getId()) ? "doris-audit" : config.getId();
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

    public List<ExceptionSummaryBackend> exceptionBackends() {
        return List.copyOf(exceptionBackends);
    }

    private void buildPromql(List<PromqlConfig> configs, BackendType type, String prefix,
                             ObjectMapper objectMapper) {
        for (int i = 0; i < configs.size(); i++) {
            PromqlConfig config = configs.get(i);
            String id = Strings.isBlank(config.getId()) ? prefix + "-" + (i + 1) : config.getId();
            String error = null;
            String header = null;
            try {
                header = authorization(config.getAuth(), prefix + "." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
            }
            if (Strings.isBlank(config.getBaseUrl()) && error == null) {
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
        return AuthorizationHeader.of(auth, secretResolver, owner);
    }

    /** 认证配置失败时，让能力判定直接返回 NOT_CONFIGURED，而不是带着空凭据去撞 401。 */
    private record AuthAwareMetricBackend(MetricBackend delegate, String configurationError)
            implements MetricBackend {

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
                        com.bigdata.modules.telemetry.common.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            return delegate.evaluate(spec);
        }

        @Override
        public MetricQueryResult fetch(MetricQuerySpec spec) {
            return delegate.fetch(spec);
        }
    }

    /** 异常摘要后端的认证失败包装：能力判定直接返回配置错误，不发请求。 */
    private record AuthAwareExceptionBackend(ExceptionSummaryBackend delegate,
                                             String configurationError)
            implements ExceptionSummaryBackend {

        @Override
        public String backendId() {
            return delegate.backendId();
        }

        @Override
        public BackendCapability evaluate(ExceptionQuerySpec spec) {
            if (configurationError != null) {
                return BackendCapability.unavailable(
                        com.bigdata.modules.telemetry.common.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            return delegate.evaluate(spec);
        }

        @Override
        public ExceptionQueryResult fetch(ExceptionQuerySpec spec) {
            return delegate.fetch(spec);
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
        public com.bigdata.modules.telemetry.common.model.BackendCapability evaluate(
                com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec spec) {
            if (configurationError != null) {
                return com.bigdata.modules.telemetry.common.model.BackendCapability.unavailable(
                        com.bigdata.modules.telemetry.common.model.CapabilityCode.NOT_CONFIGURED,
                        configurationError, List.of());
            }
            return delegate.evaluate(spec);
        }

        @Override
        public com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult fetch(
                com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec spec) {
            return delegate.fetch(spec);
        }
    }
}
