package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.common.model.Target;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlinkRestMetadataProviderTest {

    @Test
    @DisplayName("scope=version：读 /overview 的 flink-version 与 flink-commit，原样返回")
    void readsVersion() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query(null,
                    Set.of(MetadataScope.VERSION), List.of(), List.of(), 100));

            assertThat(result.versions()).singleElement().satisfies(version -> {
                assertThat(version.rawVersion()).isEqualTo("2.2.0");
                assertThat(version.buildHash()).isEqualTo("5a33689");
                assertThat(version.componentId()).isEqualTo("flink.jobmanager");
                assertThat(version.source()).isEqualTo("flink-rest:overview.flink-version");
            });
            assertThat(result.configs()).isEmpty();
        }
    }

    @Test
    @DisplayName("scope=config：flink section 返回 jobmanager 有效配置，resources 返回 CPU/内存分配")
    void readsConfigAndResources() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query(null,
                    Set.of(MetadataScope.CONFIG), List.of(), List.of(), 200));

            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.section()).isEqualTo("flink");
                assertThat(entry.key()).isEqualTo("taskmanager.memory.process.size");
                assertThat(entry.value()).isEqualTo("2048m");
                assertThat(entry.source()).isEqualTo("flink-rest:jobmanager.config");
            });
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.section()).isEqualTo("resources");
                assertThat(entry.key()).isEqualTo("cluster.slots.total");
                assertThat(entry.value()).isEqualTo("8");
            });
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.key()).isEqualTo("tm-1.cpu-cores");
                assertThat(entry.value()).isEqualTo("8.0");
                assertThat(entry.valueType()).isEqualTo("number");
            });
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.key()).isEqualTo("tm-1.memory.total-process-bytes");
                assertThat(entry.value()).isEqualTo("2147483648");
                assertThat(entry.valueType()).isEqualTo("bytes");
            });
        }
    }

    @Test
    @DisplayName("scope=config：带 ref.jobId 时返回 job section 的并行度与执行配置")
    void readsJobConfig() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query("job-1",
                    Set.of(MetadataScope.CONFIG), List.of("job"), List.of(), 100));

            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.section()).isEqualTo("job");
                assertThat(entry.key()).isEqualTo("job.state");
                assertThat(entry.value()).isEqualTo("RUNNING");
            });
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.key()).isEqualTo("execution.job-parallelism");
                assertThat(entry.value()).isEqualTo("4");
                assertThat(entry.source()).isEqualTo("flink-rest:jobs/job-1/config");
            });
        }
    }

    @Test
    @DisplayName("scope=config：只请求 job 但缺 ref.jobId 时给出明确告警，不静默丢 section")
    void warnsWhenJobSectionWithoutJobId() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query(null,
                    Set.of(MetadataScope.CONFIG), List.of("job"), List.of(), 100));

            assertThat(result.configs()).isEmpty();
            assertThat(result.warnings()).anySatisfy(warning ->
                    assertThat(warning).contains("ref.jobId"));
        }
    }

    @Test
    @DisplayName("scope=config：sections 与 keys 过滤生效")
    void filtersBySectionAndKeys() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query(null,
                    Set.of(MetadataScope.CONFIG), List.of("flink"),
                    List.of("parallelism.default"), 100));

            assertThat(result.configs()).singleElement().satisfies(entry -> {
                assertThat(entry.section()).isEqualTo("flink");
                assertThat(entry.key()).isEqualTo("parallelism.default");
                assertThat(entry.value()).isEqualTo("1");
            });
        }
    }

    @Test
    @DisplayName("配置条数触达上限：带截断告警")
    void warnsWhenConfigTruncated() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetadataFetchResult result = provider(flink).fetch(query(null,
                    Set.of(MetadataScope.CONFIG), List.of(), List.of(), 2));

            assertThat(result.configs()).hasSize(2);
            assertThat(result.warnings()).anySatisfy(warning ->
                    assertThat(warning).contains("达到上限"));
        }
    }

    @Test
    @DisplayName("能力声明同时支持版本与配置，目录条目覆盖 JM 与 TM")
    void declaresVersionAndConfig() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            FlinkRestMetadataProvider provider = provider(flink);
            assertThat(provider.capability().supportsVersion()).isTrue();
            assertThat(provider.capability().supportsConfig()).isTrue();
            assertThat(provider.contribute()).hasSize(2)
                    .allSatisfy(entry -> assertThat(entry.versionSupported()).isTrue());
        }
    }

    // ------------------------------------------------------------------ 辅助

    private FlinkRestMetadataProvider provider(FakeFlink flink) {
        TelemetryBackendProperties properties = new TelemetryBackendProperties();
        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-rest");
        config.setBaseUrl(flink.baseUrl());
        properties.setFlinkRest(List.of(config));
        SecretResolver resolver = new SecretResolver(new StandardEnvironment());
        return new FlinkRestMetadataProvider(properties, resolver, new ObjectMapper());
    }

    private MetadataQuery query(String jobId, Set<MetadataScope> scopes, List<String> sections,
                                List<String> keys, int limit) {
        Map<String, String> ref = jobId == null ? Map.of() : Map.of("jobId", jobId);
        return new MetadataQuery(
                new MetadataTarget(new Target("flink", "flink.jobmanager", "cluster"), ref),
                scopes, sections, keys, limit, Duration.ofSeconds(5));
    }

    private static final class FakeFlink implements AutoCloseable {

        private final HttpServer server;

        private FakeFlink() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/overview", exchange -> respond(exchange, """
                    {"taskmanagers":1,"slots-total":8,"slots-available":6,
                     "flink-version":"2.2.0","flink-commit":"5a33689"}
                    """));
            server.createContext("/jobmanager/config", exchange -> respond(exchange, """
                    [{"key":"taskmanager.memory.process.size","value":"2048m"},
                     {"key":"jobmanager.memory.process.size","value":"1024m"},
                     {"key":"parallelism.default","value":"1"}]
                    """));
            server.createContext("/taskmanagers", exchange -> respond(exchange, """
                    {"taskmanagers":[{"id":"tm-1","slotsNumber":8,"freeSlots":6,
                      "totalResource":{"cpuCores":8.0,"taskHeapMemory":537,
                        "managedMemory":634,"networkMemory":158},
                      "hardware":{"cpuCores":6,"physicalMemory":1610612736},
                      "memoryConfiguration":{"taskHeap":563714445,
                        "managedMemory":665719939,"networkMemory":166429984,
                        "jvmMetaspace":268435456,"jvmOverhead":214748368,
                        "totalFlinkMemory":1664299824,"totalProcessMemory":2147483648}}]}
                    """));
            server.createContext("/jobs/job-1", exchange -> respond(exchange, """
                    {"jid":"job-1","name":"demo","state":"RUNNING","job-type":"STREAMING",
                     "maxParallelism":-1,"vertices":[]}
                    """));
            server.createContext("/jobs/job-1/config", exchange -> respond(exchange, """
                    {"jid":"job-1","name":"demo","execution-config":{
                      "restart-strategy":"Cluster level default restart strategy",
                      "job-parallelism":4,"object-reuse-mode":false,"user-config":{}}}
                    """));
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
