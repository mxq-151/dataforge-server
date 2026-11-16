package com.bigdata.modules.telemetry.service.metadata.server;

import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.support.MetadataMasker;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.metadata.support.SensitiveDataMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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

/**
 * Ambari provider 单测。
 *
 * <p><b>未对真实 Ambari 验证</b>：这里的响应是按 Ambari REST 契约录制的 fixture，
 * 不代表已在真实集群跑通。接入真实实例后需要重新核对字段与 desired_configs 结构。</p>
 */
class AmbariMetadataProviderTest {

    private HttpServer server;
    private AmbariMetadataProvider provider;
    private volatile String stackVersionResponse;

    @BeforeEach
    void setUp() throws Exception {
        stackVersionResponse = "{\"items\":[]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        config.put("cluster", "c1");
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(Map.of("ambari", config));
        provider = new AmbariMetadataProvider(new ProviderSettings(properties), null,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("未对真实 Ambari 验证：host_component 版本 + desired_configs 配置")
    void readsHostComponentVersionAndConfigs() {
        MetadataFetchResult result = provider.fetch(query("hdfs", "hdfs.namenode", "10.0.0.1"));

        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("3.3.6");
            assertThat(version.source()).isEqualTo("ambari:host_components");
        });
        assertThat(result.configs()).extracting(entry -> entry.section() + "/" + entry.key())
                .containsExactlyInAnyOrder("hdfs-site/dfs.replication",
                        "hdfs-site/ssl.server.keystore.password");
    }

    @Test
    @DisplayName("未对真实 Ambari 验证：组件版本缺失时回退栈版本，并明确标注")
    void fallsBackToStackVersionWithWarning() {
        stackVersionResponse = "{\"items\":[{\"ClusterStackVersions\":{\"version\":\"3.3.6-2\"}}]}";
        MetadataFetchResult result = provider.fetch(query("hdfs", "hdfs.datanode", "10.0.0.2"));

        assertThat(result.versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("3.3.6-2");
            assertThat(version.source()).isEqualTo("ambari:stack_versions");
        });
        assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("AMBARI_STACK_VERSION"));
    }

    @Test
    @DisplayName("未对真实 Ambari 验证：provider 声明的 ssl.* 规则 + 默认规则叠加脱敏")
    void providerRulesMaskSslValues() {
        MetadataFetchResult result = provider.fetch(query("hdfs", "hdfs.namenode", "10.0.0.1"));
        MetadataMasker masker = new MetadataMasker(new SensitiveDataMasker());

        String masked = result.configs().stream()
                .filter(entry -> "ssl.server.keystore.password".equals(entry.key()))
                .map(entry -> masker.mask(entry.section(), entry.key(), entry.value(),
                        entry.sensitiveHint(), provider.capability().maskingRules()).value())
                .findFirst().orElseThrow();

        assertThat(masked).isEqualTo("***");
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body;
        if (path.endsWith("/host_components")) {
            String component = path.contains("/10.0.0.2/") ? "DATANODE" : "NAMENODE";
            String version = path.contains("/10.0.0.2/") ? "" : "3.3.6";
            body = "{\"items\":[{\"HostRoles\":{\"component_name\":\"" + component
                    + "\",\"version\":\"" + version + "\"}}]}";
        } else if (path.endsWith("/stack_versions")) {
            body = stackVersionResponse;
        } else if (path.contains("/services/") && path.contains("/components/")) {
            body = "{\"ServiceComponentInfo\":{\"desired_configs\":"
                    + "{\"hdfs-site\":{\"tag\":\"v12\"}}}}";
        } else if (path.endsWith("/configurations") && query != null) {
            body = "{\"items\":[{\"Configuration\":{\"type\":\"hdfs-site\",\"tag\":\"v12\","
                    + "\"properties\":{\"dfs.replication\":\"3\","
                    + "\"ssl.server.keystore.password\":\"keystore-pass\"}}}]}";
        } else {
            body = "{\"items\":[]}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private MetadataQuery query(String serviceType, String componentId, String hostId) {
        return new MetadataQuery(new MetadataTarget(
                new Target(serviceType, componentId, hostId), Map.of("cluster", "c1")),
                EnumSet.of(MetadataScope.VERSION, MetadataScope.CONFIG),
                List.of(), List.of(), 500, Duration.ofSeconds(2));
    }
}
