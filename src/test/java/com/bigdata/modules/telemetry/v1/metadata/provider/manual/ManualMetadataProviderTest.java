package com.bigdata.modules.telemetry.v1.metadata.provider.manual;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.metadata.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataTarget;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualMetadataProviderTest {

    @Test
    @DisplayName("按配置返回手填的版本与配置，未配置的 target 返回 NOT_CONFIGURED")
    void readsConfiguredEntries() {
        ManualMetadataProvider provider = provider();

        MetadataFetchResult result = provider.fetch(query("clickhouse", "clickhouse.server"));
        assertThat(result.versions()).singleElement().satisfies(version ->
                assertThat(version.rawVersion()).isEqualTo("24.8.14.39"));
        assertThat(result.configs()).singleElement()
                .satisfies(config -> assertThat(config.key()).isEqualTo("max_connections"));

        assertThatThrownBy(() -> provider.fetch(query("doris", "doris.fe")))
                .isInstanceOf(BackendException.class)
                .satisfies(error -> assertThat(((BackendException) error).getCode())
                        .isEqualTo(CapabilityCode.NOT_CONFIGURED));
    }

    @Test
    @DisplayName("目录条目来自配置，不伪造未配置的组件")
    void catalogComesFromEntries() {
        assertThat(provider().contribute()).singleElement().satisfies(entry -> {
            assertThat(entry.serviceType()).isEqualTo("clickhouse");
            assertThat(entry.componentId()).isEqualTo("clickhouse.server");
            assertThat(entry.providerIds()).containsExactly("manual");
        });
    }

    private ManualMetadataProvider provider() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("service-type", "clickhouse");
        entry.put("component-id", "clickhouse.server");
        entry.put("host-id", "ch-1");
        entry.put("raw-version", "24.8.14.39");
        entry.put("vendor", "ClickHouse");
        entry.put("distribution", "clickhouse-server");
        entry.put("configs", List.of(Map.of("section", "server_settings",
                "key", "max_connections", "value", "4096")));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("entries", List.of(entry));
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(Map.of("manual", config));
        return new ManualMetadataProvider(new ProviderSettings(properties));
    }

    private MetadataQuery query(String serviceType, String componentId) {
        return new MetadataQuery(new MetadataTarget(
                new Target(serviceType, componentId, "ch-1"), Map.of()),
                EnumSet.of(MetadataScope.VERSION, MetadataScope.CONFIG),
                List.of(), List.of(), 500, Duration.ofSeconds(2));
    }
}
