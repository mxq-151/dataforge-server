package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparkShsTargetDiscoveryProviderTest {

    @Test
    @DisplayName("命中：任务名精确匹配，返回 applicationId 与完成态")
    void exactHit() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            List<RawTarget> targets = provider(shs).discover(query("etl_a"));

            assertThat(targets).singleElement().satisfies(target -> {
                assertThat(target.serviceType()).isEqualTo("spark");
                assertThat(target.componentId()).isEqualTo("spark.driver");
                assertThat(target.ref()).containsEntry("applicationId", "app-1");
                assertThat(target.name()).isEqualTo("etl_a");
                assertThat(target.state()).isEqualTo("COMPLETED");
            });
        }
    }

    @Test
    @DisplayName("歧义：无精确命中时模糊匹配返回多条，由编排器判定")
    void fuzzyAmbiguity() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            List<RawTarget> targets = provider(shs).discover(query("etl"));

            assertThat(targets).hasSize(2);
            assertThat(targets).extracting(RawTarget::name).containsExactly("etl_a", "etl_b");
        }
    }

    @Test
    @DisplayName("空清单：服务可用但没有应用时返回空，不伪造")
    void emptyList() throws IOException {
        try (FakeShs shs = new FakeShs("[]")) {
            assertThat(provider(shs).discover(query("etl"))).isEmpty();
        }
    }

    @Test
    @DisplayName("未配置：启用但缺 base-url 时 NOT_CONFIGURED + 配置项")
    void notConfigured() {
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enabled", true);
        properties.getProviders().put(SparkShsTargetDiscoveryProvider.PROVIDER_ID, block);
        SparkShsTargetDiscoveryProvider provider = new SparkShsTargetDiscoveryProvider(
                new ProviderSettings(properties),
                new SecretResolver(new StandardEnvironment()), new ObjectMapper());

        assertThatThrownBy(() -> provider.discover(query("etl")))
                .isInstanceOf(BackendException.class)
                .satisfies(error -> {
                    BackendException backend = (BackendException) error;
                    assertThat(backend.getCode()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
                    assertThat(backend.getRequirements())
                            .anySatisfy(requirement -> assertThat(requirement.key())
                                    .isEqualTo("telemetry.metadata.providers.spark-shs.base-url"));
                });
    }

    // ------------------------------------------------------------------ 辅助

    private SparkShsTargetDiscoveryProvider provider(FakeShs shs) {
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enabled", true);
        block.put("base-url", shs.baseUrl());
        properties.getProviders().put(SparkShsTargetDiscoveryProvider.PROVIDER_ID, block);
        return new SparkShsTargetDiscoveryProvider(new ProviderSettings(properties),
                new SecretResolver(new StandardEnvironment()), new ObjectMapper());
    }

    private TargetDiscoveryQuery query(String taskName) {
        return new TargetDiscoveryQuery(taskName, "auto", Duration.ofSeconds(5));
    }

    private static final class FakeShs implements AutoCloseable {

        private final HttpServer server;

        private FakeShs() throws IOException {
            this("""
                    [{"id":"app-1","name":"etl_a","attempts":[
                        {"attemptId":1,"completed":true},{"attemptId":2,"completed":true}]},
                     {"id":"app-2","name":"etl_b","attempts":[
                        {"attemptId":1,"completed":false}]}]
                    """);
        }

        private FakeShs(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/applications", exchange -> respond(exchange, body));
            server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
