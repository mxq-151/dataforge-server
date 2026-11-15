package com.bigdata.modules.telemetry.v1.backend.es;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.EsConfig;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogFilter;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchLogBackendTest {

    private static final String GOOD_MAPPING = """
            {"dataforge-logs-2026.11.15":{"mappings":{"properties":{
              "@timestamp":{"type":"date"},
              "stream":{"type":"keyword"},
              "service_type":{"type":"keyword"},
              "component_id":{"type":"keyword"},
              "host_id":{"type":"keyword"},
              "message":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":32766}}}
            }}}}
            """;

    private static final String BAD_MAPPING = """
            {"dataforge-logs-2026.11.15":{"mappings":{"properties":{
              "@timestamp":{"type":"date"},
              "stream":{"type":"keyword"},
              "service_type":{"type":"keyword"},
              "component_id":{"type":"keyword"},
              "host_id":{"type":"keyword"},
              "message":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":256}}}
            }}}}
            """;

    @Test
    @DisplayName("PIT + search_after：返回首页与游标，请求体带 pit 与 wildcard 过滤")
    void searchAndPagination() throws IOException {
        try (FakeEs es = new FakeEs(GOOD_MAPPING)) {
            ElasticsearchLogBackend backend = backend(es);
            LogQuerySpec spec = spec(null);

            BackendCapability capability = backend.evaluate(spec);
            assertThat(capability.isAvailable()).isTrue();

            LogQueryResult first = backend.fetch(spec);
            assertThat(first.entries()).hasSize(2);
            assertThat(first.truncated()).isTrue();
            assertThat(first.nextCursor()).isNotBlank();
            assertThat(first.entries().get(0).message()).isEqualTo("checkpoint failed");

            String firstBody = es.searchBodies.get(0);
            assertThat(firstBody).contains("\"pit\":{\"id\":\"pit-1\",\"keep_alive\":\"2m\"}");
            assertThat(firstBody).contains(
                    "\"wildcard\":{\"message.keyword\":{\"value\":\"*checkpoint*\",\"case_insensitive\":true}}");
            assertThat(firstBody).contains("\"term\":{\"stream\":\"stderr\"}");
            assertThat(firstBody).contains("\"term\":{\"service_type\":\"flink\"}");
            assertThat(firstBody).contains("\"track_total_hits\":false");
            assertThat(firstBody).doesNotContain("search_after");

            LogQueryResult second = backend.fetch(spec(first.nextCursor()));
            assertThat(second.entries()).isNotEmpty();
            assertThat(es.searchBodies.get(1)).contains("\"search_after\":");
        }
    }

    @Test
    @DisplayName("映射 ignore_above 不足：能力判定直接 ES_MAPPING_INCOMPATIBLE")
    void mappingIncompatible() throws IOException {
        try (FakeEs es = new FakeEs(BAD_MAPPING)) {
            BackendCapability capability = backend(es).evaluate(spec(null));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.ES_MAPPING_INCOMPATIBLE);
            assertThat(capability.requirements()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("用户输入的通配符按字面量转义，不当成 wildcard 语法")
    void escapesUserWildcard() {
        ObjectMapper mapper = new ObjectMapper();
        LogQuerySpec spec = new LogQuerySpec(new Target("flink", "flink.taskmanager", "cluster"),
                LogDataType.SERVICE, "stderr", null,
                new LogFilter(List.of("a*b?"), List.of(), LogFilter.MatchMode.ALL,
                false, false), null, 10, 1024, Map.of(), null);

        String body = EsQueryBuilder.build(spec, null, null, 11, mapper).toString();

        assertThat(body).contains("\"value\":\"*a\\\\*b\\\\?*\"");
    }

    // ------------------------------------------------------------------ 辅助

    private ElasticsearchLogBackend backend(FakeEs es) {
        EsConfig config = new EsConfig();
        config.setId("es-logs");
        config.setUrls(List.of(es.baseUrl()));
        config.setIndex("dataforge-logs-*");
        config.getLimits().setMappingCacheTtlMs(0);
        return new ElasticsearchLogBackend("es-logs", config, null, new ObjectMapper());
    }

    private LogQuerySpec spec(String cursor) {
        return new LogQuerySpec(new Target("flink", "flink.taskmanager", "tm-1"),
                LogDataType.SERVICE, "stderr",
                new TimeRange(Instant.parse("2026-11-15T01:00:00Z"),
                        Instant.parse("2026-11-15T02:00:00Z")),
                new LogFilter(List.of("checkpoint"), List.of(), LogFilter.MatchMode.ALL,
                        false, false),
                cursor, 2, 1024 * 1024, Map.of(), null);
    }

    private static final class FakeEs implements AutoCloseable {

        private final HttpServer server;
        private final List<String> searchBodies = new CopyOnWriteArrayList<>();

        private FakeEs(String mapping) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> respond(exchange, 200,
                    "{\"version\":{\"number\":\"8.13.4\"}}"));
            server.createContext("/dataforge-logs-*/_mapping", exchange -> respond(exchange, 200, mapping));
            server.createContext("/dataforge-logs-*/_pit", exchange -> respond(exchange, 200,
                    "{\"id\":\"pit-1\"}"));
            server.createContext("/_search", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                searchBodies.add(body);
                respond(exchange, 200, body.contains("search_after") ? hits(2) : hits(3));
            });
            server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static String hits(int count) {
            StringBuilder hits = new StringBuilder();
            String[] messages = {"checkpoint failed", "checkpoint retry", "checkpoint ok"};
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    hits.append(',');
                }
                hits.append("{\"_source\":{\"@timestamp\":\"2026-11-15T01:00:0")
                        .append(5 + i)
                        .append("Z\",\"stream\":\"stderr\",\"host_id\":\"tm-1\",\"message\":\"")
                        .append(messages[i])
                        .append("\"},\"sort\":[")
                        .append(1763173205000L + i)
                        .append(',')
                        .append(i + 1)
                        .append("]}");
            }
            return "{\"took\":1,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":["
                    + hits + "]}}";
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
