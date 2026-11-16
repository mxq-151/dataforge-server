package com.bigdata.modules.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;

/**
 * metadata 查询配置（{@code telemetry.metadata.*}）。
 *
 * <p>{@code providers} 是自由结构的配置块，由 provider 通过 {@code ProviderSettings} 自行解析；
 * 新增 provider 不需要改本类。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry.metadata")
public class TelemetryMetadataProperties {

    private Query query = new Query();
    private Map<String, Map<String, Object>> providers = new LinkedHashMap<>();

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Map<String, Map<String, Object>> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Map<String, Object>> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /** 查询级限制。 */
    public static class Query {

        /** 单次请求的 target 上限。 */
        private int maxTargets = 20;
        /** 单个 target 的配置条数上限。 */
        private int maxConfigItems = 500;
        /** 单个 target 的指标名发现条数上限。 */
        private int maxMetrics = 5000;
        /** 单次 provider 调用超时。 */
        private long timeoutMs = 10000;

        public int getMaxTargets() {
            return maxTargets;
        }

        public void setMaxTargets(int maxTargets) {
            this.maxTargets = maxTargets;
        }

        public int getMaxConfigItems() {
            return maxConfigItems;
        }

        public void setMaxConfigItems(int maxConfigItems) {
            this.maxConfigItems = maxConfigItems;
        }

        public int getMaxMetrics() {
            return maxMetrics;
        }

        public void setMaxMetrics(int maxMetrics) {
            this.maxMetrics = maxMetrics;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
