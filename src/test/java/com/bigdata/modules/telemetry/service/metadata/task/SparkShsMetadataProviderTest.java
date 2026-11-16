package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.model.Target;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparkShsMetadataProviderTest {

    @Test
    @DisplayName("scope=config：读 SHS environment，按 section 展平键值对")
    void readsEnvironment() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            MetadataFetchResult result = provider(shs).fetch(query("app-1", List.of("spark", "hadoop")));

            assertThat(result.versions()).isEmpty();
            assertThat(result.configs()).extracting("section")
                    .containsOnly("spark", "hadoop");
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.key()).isEqualTo("spark.executor.memory");
                assertThat(entry.value()).isEqualTo("4g");
                assertThat(entry.valueType()).isEqualTo("string");
                assertThat(entry.source()).isEqualTo("spark-shs:sparkProperties");
            });
            assertThat(result.configs()).anySatisfy(entry -> {
                assertThat(entry.key()).isEqualTo("fs.defaultFS");
                assertThat(entry.value()).isEqualTo("hdfs://nn:8020");
            });
        }
    }

    @Test
    @DisplayName("scope=config：keys 过滤生效，未请求 section 不返回")
    void filtersBySectionAndKeys() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            MetadataQuery query = new MetadataQuery(
                    new MetadataTarget(new Target("spark", "spark.driver", "cluster"),
                            Map.of("applicationId", "app-1")),
                    Set.of(MetadataScope.CONFIG), List.of("spark"), List.of("spark.executor.memory"),
                    100, Duration.ofSeconds(5));

            MetadataFetchResult result = provider(shs).fetch(query);
            assertThat(result.configs()).singleElement()
                    .satisfies(entry -> assertThat(entry.key()).isEqualTo("spark.executor.memory"));
        }
    }

    @Test
    @DisplayName("缺少 ref.applicationId：INVALID_ARGUMENT，不发 HTTP 请求")
    void requiresApplicationId() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            MetadataQuery query = new MetadataQuery(
                    MetadataTarget.of(new Target("spark", "spark.driver", "cluster")),
                    Set.of(MetadataScope.CONFIG), List.of(), List.of(), 100, Duration.ofSeconds(5));

            assertThatThrownBy(() -> provider(shs).fetch(query))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> assertThat(((BackendException) error).getCode())
                            .isEqualTo(CapabilityCode.INVALID_ARGUMENT));
        }
    }

    @Test
    @DisplayName("scope=version：读应用详情的 appSparkVersion，原样返回不猜")
    void readsSparkVersion() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            MetadataQuery versionQuery = new MetadataQuery(
                    new MetadataTarget(new Target("spark", "spark.driver", "cluster"),
                            Map.of("applicationId", "app-1")),
                    Set.of(MetadataScope.VERSION), List.of(), List.of(), 100,
                    Duration.ofSeconds(5));

            MetadataFetchResult result = provider(shs).fetch(versionQuery);

            assertThat(result.versions()).singleElement().satisfies(version -> {
                assertThat(version.rawVersion()).isEqualTo("3.5.9");
                assertThat(version.componentId()).isEqualTo("spark.driver");
                assertThat(version.hostId()).isEqualTo("cluster");
                assertThat(version.source()).isEqualTo("spark-shs:appSparkVersion");
            });
            // 只要版本时不去拉 environment
            assertThat(result.configs()).isEmpty();
        }
    }

    @Test
    @DisplayName("能力声明同时支持版本与配置，目录条目 versionSupported=true")
    void declaresVersionAndConfig() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            SparkShsMetadataProvider provider = provider(shs);
            assertThat(provider.capability().supportsVersion()).isTrue();
            assertThat(provider.capability().supportsConfig()).isTrue();
            assertThat(provider.contribute()).allSatisfy(entry ->
                    assertThat(entry.versionSupported()).isTrue());
        }
    }

    @Test
    @DisplayName("配置条数触达上限：带截断告警，不静默丢")
    void warnsWhenConfigTruncated() throws IOException {
        try (FakeShs shs = new FakeShs()) {
            MetadataQuery limited = new MetadataQuery(
                    new MetadataTarget(new Target("spark", "spark.driver", "cluster"),
                            Map.of("applicationId", "app-1")),
                    Set.of(MetadataScope.CONFIG), List.of(), List.of(), 1, Duration.ofSeconds(5));

            MetadataFetchResult result = provider(shs).fetch(limited);

            assertThat(result.configs()).hasSize(1);
            assertThat(result.warnings()).anySatisfy(warning ->
                    assertThat(warning).contains("达到上限"));
        }
    }

    // ------------------------------------------------------------------ 辅助

    private SparkShsMetadataProvider provider(FakeShs shs) {
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enabled", true);
        block.put("base-url", shs.baseUrl());
        properties.getProviders().put(SparkShsMetadataProvider.PROVIDER_ID, block);
        ProviderSettings settings = new ProviderSettings(properties);
        SecretResolver resolver = new SecretResolver(new StandardEnvironment());
        return new SparkShsMetadataProvider(settings, resolver, new ObjectMapper());
    }

    private MetadataQuery query(String applicationId, List<String> sections) {
        return new MetadataQuery(
                new MetadataTarget(new Target("spark", "spark.driver", "cluster"),
                        Map.of("applicationId", applicationId)),
                Set.of(MetadataScope.CONFIG), sections, List.of(), 100, Duration.ofSeconds(5));
    }

    private static final class FakeShs implements AutoCloseable {

        private final HttpServer server;

        private FakeShs() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/applications/app-1", exchange -> respond(exchange, """
                    {"id":"app-1","name":"demo",
                     "attempts":[{"attemptId":1,"completed":true,"appSparkVersion":"3.5.9"}]}
                    """));
            server.createContext("/api/v1/applications/app-1/environment", exchange -> respond(exchange, ENVIRONMENT));
            server.createContext("/api/v1/applications/app-1/1/environment", exchange -> respond(exchange, ENVIRONMENT));
            server.start();
        }

        private static final String ENVIRONMENT = """
                {"sparkProperties":[
                   ["spark.executor.memory","4g"],
                   ["spark.executor.cores","2"]],
                 "hadoopProperties":[
                   ["fs.defaultFS","hdfs://nn:8020"]],
                 "systemProperties":[
                   ["java.version","17.0.9"]],
                 "classpathEntries":[
                   ["/opt/spark/jars/*"]]}
                """;

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
