package com.bigdata.modules.telemetry.v1.discovery;

import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.config.ConfiguredBackendFactory;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.PromqlConfig;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromqlMetricDiscoveryProviderTest {

    private HttpServer server;
    private PromqlMetricDiscoveryProvider provider;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("/api/v1/metadata 提供类型与单位")
    void discoversNamesWithMetadata() throws IOException {
        startServer(Map.of(
                "/api/v1/label/__name__/values",
                "{\"status\":\"success\",\"data\":[\"kafka_a_total\",\"kafka_b\"]}",
                "/api/v1/metadata",
                "{\"status\":\"success\",\"data\":{"
                        + "\"kafka_a_total\":[{\"type\":\"counter\",\"help\":\"A\",\"unit\":\"count\"}],"
                        + "\"kafka_b\":[{\"type\":\"gauge\",\"help\":\"B\"}]}}"), List.of());
        MetricDiscoveryResult result = provider.discover(query());

        assertThat(result.supported()).isTrue();
        assertThat(result.manual()).isFalse();
        assertThat(result.metrics()).extracting(DiscoveredMetric::name)
                .containsExactly("kafka_a_total", "kafka_b");
        assertThat(result.metrics()).extracting(DiscoveredMetric::type)
                .containsExactly("counter", "gauge");
        assertThat(result.metrics().get(0).unit()).isEqualTo("count");
    }

    @Test
    @DisplayName("metadata 为空时回退抓取文本的 # TYPE 行")
    void fallsBackToScrapeTextTypes() throws IOException {
        startServer(Map.of(
                "/api/v1/label/__name__/values",
                "{\"status\":\"success\",\"data\":[\"kafka_c_total\"]}",
                "/api/v1/metadata", "{\"status\":\"success\",\"data\":{}}",
                "/metrics", "# HELP kafka_c_total C\n# TYPE kafka_c_total counter\nkafka_c_total 1\n"),
                List.of("http://127.0.0.1:%d/metrics"));
        MetricDiscoveryResult result = provider.discover(query());

        assertThat(result.supported()).isTrue();
        assertThat(result.metrics()).hasSize(1);
        assertThat(result.metrics().get(0).type()).isEqualTo("counter");
        assertThat(result.warnings()).anySatisfy(warning ->
                assertThat(warning.code()).isEqualTo("METRIC_METADATA_UNAVAILABLE"));
    }

    private void startServer(Map<String, String> routes, List<String> metadataUrlTemplates)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routes.forEach((path, body) -> server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }));
        server.start();
        int port = server.getAddress().getPort();

        PromqlConfig config = new PromqlConfig();
        config.setId("vm-test");
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setPathPrefix("/api/v1");
        config.setDiscoveryServiceTypes(List.of("kafka"));
        config.setMetricMetadataUrls(metadataUrlTemplates.stream()
                .map(template -> template.replace("%d", String.valueOf(port))).toList());

        TelemetryBackendProperties properties = new TelemetryBackendProperties();
        properties.setVictoriametrics(List.of(config));
        ObjectMapper objectMapper = new ObjectMapper();
        provider = new PromqlMetricDiscoveryProvider(properties,
                new ConfiguredBackendFactory(properties, objectMapper,
                        new SecretResolver(new org.springframework.core.env.StandardEnvironment())),
                objectMapper);
    }

    private MetricDiscoveryQuery query() {
        return new MetricDiscoveryQuery(new Target("kafka", "kafka.broker", "cluster"),
                Map.of(), java.time.Duration.ofSeconds(5), 100);
    }
}
