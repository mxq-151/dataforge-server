package com.bigdata.modules.telemetry.service.metadata.server;

import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.model.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseMetadataProviderTest {

    private HttpServer server;
    private ClickHouseMetadataProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body;
            if (query != null && query.contains("version()")) {
                body = "{\"meta\":[{\"name\":\"version()\",\"type\":\"String\"}],"
                        + "\"data\":[{\"version()\":\"24.8.14.39\"}],\"rows\":1}";
            } else {
                body = "{\"meta\":[{\"name\":\"name\"},{\"name\":\"value\"}],"
                        + "\"data\":[{\"name\":\"max_connections\",\"value\":\"4096\","
                        + "\"type\":\"UInt64\",\"changed\":0},"
                        + "{\"name\":\"auth_token\",\"value\":\"plain-token\","
                        + "\"type\":\"String\",\"changed\":0}],\"rows\":2}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        config.put("sections", List.of("settings"));
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(Map.of("clickhouse-http", config));
        provider = new ClickHouseMetadataProvider(new ProviderSettings(properties), null,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("version() 与 system.settings 真实结构解析为原始值")
    void parsesVersionAndSettings() {
        MetadataFetchResult result = provider.fetch(query(List.of()));

        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("24.8.14.39");
            assertThat(version.source()).isEqualTo("clickhouse:version()");
            assertThat(version.vendor()).isEqualTo("ClickHouse");
        });
        assertThat(result.configs()).hasSize(2);
        assertThat(result.configs()).filteredOn(entry -> "auth_token".equals(entry.key()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.value()).isEqualTo("plain-token");
                    assertThat(entry.source()).isEqualTo("clickhouse:system.settings");
                });
    }

    @Test
    @DisplayName("未知 section 只给 warning，不拼进 SQL")
    void unknownSectionIsRejected() {
        MetadataFetchResult result = provider.fetch(query(List.of("settings", "bogus")));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("bogus"));
        assertThat(result.configs()).isNotEmpty();
    }

    @Test
    @DisplayName("provider 只返回原始值，脱敏由核心完成")
    void returnsRawValues() {
        MetadataFetchResult result = provider.fetch(query(List.of()));

        assertThat(result.configs()).extracting(entry -> entry.key() + "=" + entry.value())
                .contains("auth_token=plain-token");
    }

    private MetadataQuery query(List<String> sections) {
        return new MetadataQuery(new MetadataTarget(
                new Target("clickhouse", "clickhouse.server", "ch-1"), Map.of()),
                EnumSet.of(MetadataScope.VERSION, MetadataScope.CONFIG),
                sections, List.of(), 500, Duration.ofSeconds(2));
    }
}
