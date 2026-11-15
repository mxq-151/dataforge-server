package com.bigdata.modules.telemetry.v1.metadata.provider.doris;

import com.bigdata.modules.telemetry.v1.metadata.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataTarget;
import com.bigdata.modules.telemetry.v1.metadata.provider.doris.DorisMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.support.JdbcMetadataClient.ReadOnlyStatement;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataSqlClient;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.v1.model.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doris provider 单测（录制行集；真实联调见验收记录：Doris 4.1.5）。
 */
class DorisMetadataProviderTest {

    @Test
    @DisplayName("SHOW FRONTENDS 的 Version 列是唯一版本来源")
    void readsVersionFromShowFrontends() {
        FakeSql sql = new FakeSql();
        sql.rows.put(ReadOnlyStatement.SHOW_FRONTENDS, List.of(row(Map.of(
                "Name", "fe1", "IP", "172.20.0.2",
                "Version", "doris-4.1.5-release-917b20be838"))));
        DorisMetadataProvider provider = provider(sql);

        MetadataFetchResult result = provider.fetch(query("doris.fe"));

        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("doris-4.1.5-release-917b20be838");
            assertThat(version.hostId()).isEqualTo("172.20.0.2");
            assertThat(version.source()).isEqualTo("doris-fe:SHOW FRONTENDS");
        });
    }

    @Test
    @DisplayName("ADMIN SHOW FRONTEND CONFIG 解析 Key/Value/Type")
    void readsFrontendConfig() {
        FakeSql sql = new FakeSql();
        sql.rows.put(ReadOnlyStatement.ADMIN_SHOW_FRONTEND_CONFIG, List.of(
                row(Map.of("Key", "auth_token", "Value", "token-value", "Type", "String")),
                row(Map.of("Key", "max_connections", "Value", "1024", "Type", "String"))));
        DorisMetadataProvider provider = provider(sql);

        MetadataFetchResult result = provider.fetch(query("doris.fe"));

        assertThat(result.configs()).hasSize(2);
        assertThat(result.configs()).filteredOn(config -> "auth_token".equals(config.key()))
                .singleElement()
                .satisfies(config -> {
                    assertThat(config.value()).isEqualTo("token-value");
                    assertThat(config.section()).isEqualTo("fe_config");
                    assertThat(config.source()).isEqualTo("doris-fe:ADMIN SHOW FRONTEND CONFIG");
                });
    }

    @Test
    @DisplayName("查 BE 时只取 SHOW BACKENDS 版本；配置明确不支持并给 warning")
    void backendOnlySupportsVersion() {
        FakeSql sql = new FakeSql();
        sql.rows.put(ReadOnlyStatement.SHOW_BACKENDS, List.of(row(Map.of(
                "Host", "172.20.0.3", "Version", "doris-4.1.5-release-917b20be838"))));
        DorisMetadataProvider provider = provider(sql);

        MetadataFetchResult result = provider.fetch(query("doris.be"));

        assertThat(result.versions()).singleElement()
                .satisfies(version -> assertThat(version.hostId()).isEqualTo("172.20.0.3"));
        assertThat(result.configs()).isEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("仅 FE 配置"));
        assertThat(sql.calls).containsExactly(ReadOnlyStatement.SHOW_BACKENDS);
    }

    @Test
    @DisplayName("keys 过滤先于 limit：目标键不会被前面的行挤出")
    void keysFilterHappensBeforeLimit() {
        FakeSql sql = new FakeSql();
        sql.rows.put(ReadOnlyStatement.ADMIN_SHOW_FRONTEND_CONFIG, List.of(
                row(Map.of("Key", "aaa", "Value", "1", "Type", "String")),
                row(Map.of("Key", "bbb", "Value", "2", "Type", "String")),
                row(Map.of("Key", "auth_token", "Value", "token-value", "Type", "String"))));
        DorisMetadataProvider provider = provider(sql);

        MetadataFetchResult result = provider.fetch(new MetadataQuery(
                new MetadataTarget(new Target("doris", "doris.fe", "doris-fe"), Map.of()),
                EnumSet.of(MetadataScope.CONFIG), List.of(), List.of("auth_token"), 1,
                Duration.ofSeconds(2)));

        assertThat(result.configs()).singleElement()
                .satisfies(config -> assertThat(config.key()).isEqualTo("auth_token"));
    }

    private DorisMetadataProvider provider(MetadataSqlClient client) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("jdbc-url", "jdbc:mysql://127.0.0.1:9030");
        config.put("username", "root");
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(Map.of("doris-fe", config));
        return new DorisMetadataProvider(new ProviderSettings(properties), client);
    }

    private MetadataQuery query(String componentId) {
        return new MetadataQuery(new MetadataTarget(
                new Target("doris", componentId, "doris-fe"), Map.of()),
                EnumSet.of(MetadataScope.VERSION, MetadataScope.CONFIG),
                List.of(), List.of(), 500, Duration.ofSeconds(2));
    }

    private Map<String, String> row(Map<String, String> values) {
        return new LinkedHashMap<>(values);
    }

    private static final class FakeSql implements MetadataSqlClient {

        private final Map<ReadOnlyStatement, List<Map<String, String>>> rows =
                new EnumMap<>(ReadOnlyStatement.class);
        private final List<ReadOnlyStatement> calls = new ArrayList<>();

        @Override
        public List<Map<String, String>> query(ReadOnlyStatement statement) {
            calls.add(statement);
            return rows.getOrDefault(statement, List.of());
        }

        @Override
        public void close() {
        }
    }
}
