package com.bigdata.modules.telemetry.service.metadata.server;

import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.spi.MetadataSqlClient;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.model.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.service.metadata.support.JdbcMetadataClient.ReadOnlyStatement;
import com.bigdata.modules.telemetry.service.metadata.support.JdbcMetadataClient;

/**
 * StarRocks provider 单测。
 *
 * <p><b>未对真实实例验证</b>：环境未部署 StarRocks，断言基于录制行集（与 Doris 协议同源）。
 * 接入真实实例后必须重新核对列名与语句兼容性。</p>
 */
class StarRocksMetadataProviderTest {

    @Test
    @DisplayName("未对真实实例验证：SHOW FRONTENDS / ADMIN SHOW FRONTEND CONFIG 按 Doris 同源协议解析")
    void parsesFrontendRowsFromFixture() {
        FakeSql sql = new FakeSql();
        sql.rows.put(ReadOnlyStatement.SHOW_FRONTENDS, List.of(row(Map.of(
                "Name", "fe-1", "IP", "172.20.1.2",
                "Version", "3.2.6-5c2a4b0"))));
        sql.rows.put(ReadOnlyStatement.ADMIN_SHOW_FRONTEND_CONFIG, List.of(
                row(Map.of("Key", "auth_token", "Value", "starrocks-token", "Type", "String"))));
        StarRocksMetadataProvider provider = new StarRocksMetadataProvider(settings(), sql);

        MetadataFetchResult result = provider.fetch(new MetadataQuery(
                new MetadataTarget(new Target("starrocks", "starrocks.fe", "fe-1"), Map.of()),
                EnumSet.of(MetadataScope.VERSION, MetadataScope.CONFIG),
                List.of(), List.of(), 500, Duration.ofSeconds(2)));

        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("3.2.6-5c2a4b0");
            assertThat(version.vendor()).isEqualTo("StarRocks");
            assertThat(version.source()).isEqualTo("starrocks-fe:SHOW FRONTENDS");
        });
        assertThat(result.configs()).singleElement()
                .satisfies(config -> assertThat(config.value()).isEqualTo("starrocks-token"));
    }

    @Test
    @DisplayName("未对真实实例验证：能力只声明 FE / BE，CN 不冒充支持")
    void capabilityCoversFeAndBeOnly() {
        StarRocksMetadataProvider provider = new StarRocksMetadataProvider(settings(), new FakeSql());

        assertThat(provider.capability().serviceTypes()).containsExactly("starrocks");
        assertThat(provider.capability().componentIds())
                .containsExactlyInAnyOrder("starrocks.fe", "starrocks.be");
        assertThat(provider.contribute()).hasSize(2);
    }

    private ProviderSettings settings() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("jdbc-url", "jdbc:mysql://127.0.0.1:9030");
        config.put("username", "root");
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(Map.of("starrocks-fe", config));
        return new ProviderSettings(properties);
    }

    private Map<String, String> row(Map<String, String> values) {
        return new LinkedHashMap<>(values);
    }

    private static final class FakeSql implements MetadataSqlClient {

        private final Map<ReadOnlyStatement, List<Map<String, String>>> rows =
                new EnumMap<>(ReadOnlyStatement.class);

        @Override
        public List<Map<String, String>> query(ReadOnlyStatement statement) {
            return rows.getOrDefault(statement, List.of());
        }

        @Override
        public void close() {
        }
    }
}
