package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.config.ConfiguredBackendFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端注册表：配置实例 + 用户自定义实现，启动期冻结并检测重复 id。
 */
@Component
public class TelemetryBackendRegistry {

    private final Map<String, MetricBackend> metricBackends;
    private final Map<String, LogBackend> logBackends;

    /** 测试/手工装配：只注册显式传入的后端。 */
    public TelemetryBackendRegistry(List<MetricBackend> metricBackends, List<LogBackend> logBackends) {
        Map<String, MetricBackend> metrics = new LinkedHashMap<>();
        for (MetricBackend backend : metricBackends) {
            register(metrics, backend.backendId(), backend, "MetricBackend");
        }
        Map<String, LogBackend> logs = new LinkedHashMap<>();
        for (LogBackend backend : logBackends) {
            register(logs, backend.backendId(), backend, "LogBackend");
        }
        this.metricBackends = Map.copyOf(metrics);
        this.logBackends = Map.copyOf(logs);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryBackendRegistry(List<MetricBackend> customMetricBackends,
                                    List<LogBackend> customLogBackends,
                                    ConfiguredBackendFactory configuredBackendFactory) {
        Map<String, MetricBackend> metrics = new LinkedHashMap<>();
        for (MetricBackend backend : configuredBackendFactory.metricBackends()) {
            register(metrics, backend.backendId(), backend, "MetricBackend");
        }
        for (MetricBackend backend : customMetricBackends) {
            register(metrics, backend.backendId(), backend, "MetricBackend");
        }
        Map<String, LogBackend> logs = new LinkedHashMap<>();
        for (LogBackend backend : configuredBackendFactory.logBackends()) {
            register(logs, backend.backendId(), backend, "LogBackend");
        }
        for (LogBackend backend : customLogBackends) {
            register(logs, backend.backendId(), backend, "LogBackend");
        }
        this.metricBackends = Map.copyOf(metrics);
        this.logBackends = Map.copyOf(logs);
    }

    public MetricBackend metric(String backendId) {
        return metricBackends.get(backendId);
    }

    public LogBackend log(String backendId) {
        return logBackends.get(backendId);
    }

    public Collection<MetricBackend> metricBackends() {
        return metricBackends.values();
    }

    public Collection<LogBackend> logBackends() {
        return logBackends.values();
    }

    private static <T> void register(Map<String, T> target, String id, T backend, String kind) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(kind + " 的 backendId 不能为空");
        }
        if (target.putIfAbsent(id, backend) != null) {
            throw new IllegalStateException("重复的 " + kind + " backendId: " + id);
        }
    }
}
