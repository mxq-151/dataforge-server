package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;

class FlinkRestTargetDiscoveryProviderTest {

    @Test
    @DisplayName("命中：任务名精确匹配，返回 jobId 与运行状态")
    void exactHit() throws IOException {
        try (FakeFlink flink = new FakeFlink("""
                {"jobs":[{"jid":"job-1","name":"etl_a","state":"RUNNING"},
                         {"jid":"job-2","name":"etl_b","state":"FINISHED"}]}
                """)) {
            List<RawTarget> targets = provider(flink).discover(query("etl_a"));

            assertThat(targets).singleElement().satisfies(target -> {
                assertThat(target.serviceType()).isEqualTo("flink");
                assertThat(target.componentId()).isEqualTo("flink.jobmanager");
                assertThat(target.ref()).containsEntry("jobId", "job-1");
                assertThat(target.state()).isEqualTo("RUNNING");
            });
        }
    }

    @Test
    @DisplayName("空清单：当前集群没有作业时返回空")
    void emptyList() throws IOException {
        try (FakeFlink flink = new FakeFlink("{\"jobs\":[]}")) {
            assertThat(provider(flink).discover(query("etl"))).isEmpty();
        }
    }

    @Test
    @DisplayName("未配置：没有 flink-rest base-url 时 NOT_CONFIGURED")
    void notConfigured() {
        FlinkRestTargetDiscoveryProvider provider = new FlinkRestTargetDiscoveryProvider(
                new TelemetryBackendProperties(), new SecretResolver(new StandardEnvironment()),
                new ObjectMapper());

        assertThat(provider.enabled()).isFalse();
        assertThatThrownBy(() -> provider.discover(query("etl")))
                .isInstanceOf(BackendException.class)
                .satisfies(error -> assertThat(((BackendException) error).getCode())
                        .isEqualTo(CapabilityCode.NOT_CONFIGURED));
    }

    // ------------------------------------------------------------------ 辅助

    private FlinkRestTargetDiscoveryProvider provider(FakeFlink flink) {
        TelemetryBackendProperties properties = new TelemetryBackendProperties();
        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-rest-test");
        config.setBaseUrl(flink.baseUrl());
        properties.getFlinkRest().add(config);
        return new FlinkRestTargetDiscoveryProvider(properties,
                new SecretResolver(new StandardEnvironment()), new ObjectMapper());
    }

    private TargetDiscoveryQuery query(String taskName) {
        return new TargetDiscoveryQuery(taskName, "auto", Duration.ofSeconds(5));
    }

    private static final class FakeFlink implements AutoCloseable {

        private final HttpServer server;

        private FakeFlink(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/jobs/overview", exchange -> respond(exchange, body));
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
