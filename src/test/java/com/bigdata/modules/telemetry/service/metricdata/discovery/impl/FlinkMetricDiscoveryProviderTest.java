package com.bigdata.modules.telemetry.service.metricdata.discovery.impl;

import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.config.ConfiguredBackendFactory;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.common.model.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;

class FlinkMetricDiscoveryProviderTest {

    private HttpServer server;
    private FlinkMetricDiscoveryProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/jobmanager/metrics", "[{\"id\":\"Status.JVM.Memory.Heap.Used\"}]");
        route("/taskmanagers", "{\"taskmanagers\":[{\"id\":\"tm-1\"}]}");
        route("/taskmanagers/tm-1/metrics", "[{\"id\":\"Status.JVM.Threads.Count\"}]");
        route("/jobs/j1/metrics", "[{\"id\":\"lastCheckpointDuration\"}]");
        route("/jobs/j1/vertices/v1/metrics",
                "[{\"id\":\"0.op.numRecordsOutPerSecond\"}]");
        server.start();

        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-test");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        TelemetryBackendProperties properties = new TelemetryBackendProperties();
        properties.setFlinkRest(List.of(config));
        ObjectMapper objectMapper = new ObjectMapper();
        provider = new FlinkMetricDiscoveryProvider(properties,
                new ConfiguredBackendFactory(properties, objectMapper,
                        new SecretResolver(new org.springframework.core.env.StandardEnvironment())),
                objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("默认只列 JM 级；job 级必须带 ref.jobId")
    void jobManagerScope() {
        MetricDiscoveryResult withoutJob = provider.discover(query("flink.jobmanager", Map.of()));
        assertThat(withoutJob.metrics()).extracting(DiscoveredMetric::name)
                .containsExactly("Status.JVM.Memory.Heap.Used");

        MetricDiscoveryResult withJob = provider.discover(query("flink.jobmanager",
                Map.of("jobId", "j1")));
        assertThat(withJob.metrics()).extracting(DiscoveredMetric::name)
                .containsExactly("Status.JVM.Memory.Heap.Used", "lastCheckpointDuration");
    }

    @Test
    @DisplayName("TM 级默认只列 TM；vertex 级必须同时带 jobId + vertexId")
    void taskManagerScope() {
        MetricDiscoveryResult tm = provider.discover(query("flink.taskmanager", Map.of()));
        assertThat(tm.metrics()).extracting(DiscoveredMetric::name)
                .containsExactly("Status.JVM.Threads.Count");

        MetricDiscoveryResult vertex = provider.discover(query("flink.taskmanager",
                Map.of("jobId", "j1", "vertexId", "v1")));
        assertThat(vertex.metrics()).extracting(DiscoveredMetric::name)
                .containsExactly("Status.JVM.Threads.Count", "0.op.numRecordsOutPerSecond");
    }

    @Test
    @DisplayName("vertexId 缺少 jobId：INVALID_ARGUMENT，拒绝全量扫描")
    void vertexWithoutJobFails() {
        MetricDiscoveryResult result = provider.discover(query("flink.taskmanager",
                Map.of("vertexId", "v1")));

        assertThat(result.supported()).isFalse();
        assertThat(result.code()).isEqualTo(com.bigdata.modules.telemetry.common.model.CapabilityCode.INVALID_ARGUMENT);
        assertThat(result.reason()).contains("ref.jobId");
    }

    private MetricDiscoveryQuery query(String componentId, Map<String, String> refs) {
        return new MetricDiscoveryQuery(
                new Target("flink", componentId, "cluster"), refs, Duration.ofSeconds(5), 100);
    }

    private void route(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
