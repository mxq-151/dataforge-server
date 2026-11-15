package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.config.ConfiguredBackendFactory;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 运行时引擎实例注册表：只实例化配置里出现的 {@code flink-rest[]} / {@code spark-rest[]}。
 *
 * <p>认证解析失败不静默使用空凭据，而是把原因带进引擎的 {@code unconfiguredReason()}，
 * 查询返回 NOT_CONFIGURED。</p>
 */
@Component
public class RuntimeEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEngineRegistry.class);

    private final List<RuntimeEngine> engines = new ArrayList<>();

    public RuntimeEngineRegistry(TelemetryBackendProperties properties,
                                 ConfiguredBackendFactory authFactory,
                                 ObjectMapper objectMapper,
                                 TelemetryCatalogRegistry catalogRegistry,
                                 RuntimeV1Properties runtimeProperties) {
        for (int i = 0; i < properties.getFlinkRest().size(); i++) {
            FlinkRestConfig config = properties.getFlinkRest().get(i);
            String id = blank(config.getId()) ? "flink-rest-" + (i + 1) : config.getId();
            String error = null;
            String header = null;
            try {
                header = authFactory.resolveAuthorization(config.getAuth(), "flink-rest." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Flink REST 运行时引擎 {} 认证配置不可用: {}", id, error);
            }
            if (blank(config.getBaseUrl()) && error == null) {
                error = "未配置 telemetry.backends.flink-rest[].base-url";
            }
            engines.add(new FlinkRuntimeEngine(id, config, header, error, objectMapper,
                    catalogRegistry, runtimeProperties));
        }
        for (int i = 0; i < properties.getSparkRest().size(); i++) {
            SparkRestConfig config = properties.getSparkRest().get(i);
            String id = blank(config.getId()) ? "spark-rest-" + (i + 1) : config.getId();
            String error = null;
            String header = null;
            try {
                header = authFactory.resolveAuthorization(config.getAuth(), "spark-rest." + id);
            } catch (IllegalStateException e) {
                error = e.getMessage();
                log.warn("Spark REST 运行时引擎 {} 认证配置不可用: {}", id, error);
            }
            if (blank(config.getBaseUrl()) && error == null) {
                error = "未配置 telemetry.backends.spark-rest[].base-url";
            }
            engines.add(new SparkRuntimeEngine(id, config, header, error, objectMapper,
                    catalogRegistry, runtimeProperties));
        }
    }

    public List<RuntimeEngine> engines() {
        return List.copyOf(engines);
    }

    public List<RuntimeEngine> enginesFor(String serviceType) {
        return engines.stream().filter(engine -> engine.serviceType().equals(serviceType)).toList();
    }

    public Optional<RuntimeEngine> resolve(String serviceType, String mode) {
        List<RuntimeEngine> candidates = enginesFor(serviceType);
        if (blank(mode)) {
            return candidates.stream().findFirst();
        }
        return candidates.stream()
                .filter(engine -> engine.mode().equalsIgnoreCase(mode.trim()))
                .findFirst();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
