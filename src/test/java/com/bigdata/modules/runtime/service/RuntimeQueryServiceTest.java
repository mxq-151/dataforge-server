package com.bigdata.modules.runtime.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.adapter.HttpLogClient;
import com.bigdata.modules.logs.adapter.HttpTextResponse;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.service.LogAccessControlService;
import com.bigdata.modules.logs.service.LogCircuitBreaker;
import com.bigdata.modules.logs.service.LogExecutorRegistry;
import com.bigdata.modules.logs.service.LogMaskingService;
import com.bigdata.modules.logs.service.SecretResolver;
import com.bigdata.modules.runtime.RuntimeTestSupport;
import com.bigdata.modules.runtime.adapter.FlinkRuntimeAdapter;
import com.bigdata.modules.runtime.adapter.RuntimeAdapterRegistry;
import com.bigdata.modules.runtime.adapter.RuntimeRestClient;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.dto.RuntimeQueryDTO;
import com.bigdata.modules.runtime.vo.RuntimeCompareVO;
import com.bigdata.modules.runtime.vo.RuntimeConfigVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import com.bigdata.modules.system.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("运行时查询编排：降级、未启用报错、配置脱敏、对比")
class RuntimeQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("上游不可达：返回 degraded 而不是抛堆栈")
    void unreachableReturnsDegraded() {
        HttpLogClient failing = (url, headers, timeout) -> {
            throw new IOException("Connection refused");
        };
        RuntimeQueryService service = service(failing, sourceConfig("http://jm:8081"));
        RuntimeSnapshotVO snapshot = service.snapshot(dto("j1", "jvm_gc"));

        assertTrue(snapshot.isDegraded());
        assertTrue(snapshot.getDegradationReason().contains("不可达"));
        assertEquals("SOURCE_UNREACHABLE", snapshot.getErrorCode());
    }

    @Test
    @DisplayName("未配置端点：400 并携带可读原因")
    void disabledSourceRejected() {
        RuntimeQueryService service = service((url, headers, timeout) -> {
            throw new IOException("should not be called");
        }, sourceConfig(""));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.snapshot(dto("j1", "jvm_gc")));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("base-url"));
    }

    @Test
    @DisplayName("配置查询：JM 配置中的密码与作业配置中的敏感键都要脱敏")
    void configMasksSecrets() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1/config", 200, """
                {"jid":"j1","name":"demo",
                 "execution-config":{"execution.checkpointing.interval":"30s","password":"p"}}
                """);
        http.on("/jobmanager/config", 200, """
                [{"key":"security.ssl.keystore-password","value":"secret123"},
                 {"key":"taskmanager.memory.process.size","value":"1024m"}]
                """);
        http.on("/taskmanagers", 200, taskManagersJson());

        RuntimeQueryService service = service(http, sourceConfig("http://jm:8081"));
        RuntimeConfigVO config = service.config(dto("j1", "jvm_gc"));

        assertTrue(config.isAvailable());
        assertEquals("******", config.getSections().get("jobmanager")
                .get("security.ssl.keystore-password"));
        assertEquals("1024m", config.getSections().get("jobmanager")
                .get("taskmanager.memory.process.size"));
        assertEquals("30s", config.getSections().get("job").get("execution.checkpointing.interval"));
        assertEquals("******", config.getSections().get("job").get("password"));
        assertEquals(1, config.getComponents().size());
    }

    @Test
    @DisplayName("时间点对比：进程内没有更早快照时明确说明不可比")
    void compareWithoutPrior() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1/metrics", 200, "[{\"id\":\"numRestarts\",\"value\":\"1\"}]");
        http.on("/taskmanagers", 200, taskManagersJson());
        http.on("/jobs/j1", 200, jobJson());

        RuntimeQueryService service = service(http, sourceConfig("http://jm:8081"));
        RuntimeQueryDTO dto = dto("j1", "failures");
        dto.setIncludeVertices(false);
        dto.setWindowSeconds(60L);

        RuntimeCompareVO compare = service.compare(dto);

        assertFalse(compare.isComparable());
        assertTrue(compare.getReason().contains("没有更早"));
        assertEquals("j1", compare.getJobId());
    }

    // ------------------------------------------------------------------

    private RuntimeQueryService service(HttpLogClient http, RuntimeProperties.Source source) {
        RuntimeProperties properties = RuntimeTestSupport.properties();
        properties.setSources(List.of(source));

        LogAccessProperties logProperties = new LogAccessProperties();
        logProperties.getCache().setEnabled(false);
        logProperties.getExecutor().setCorePoolSize(1);
        logProperties.getExecutor().setMaxPoolSize(1);
        logProperties.getExecutor().setQueueCapacity(8);

        RuntimeSourceCatalog catalog =
                new RuntimeSourceCatalog(properties, new SecretResolver(new StandardEnvironment()));
        RuntimeAdapterRegistry registry = new RuntimeAdapterRegistry(List.of(
                new FlinkRuntimeAdapter(new RuntimeRestClient(http, MAPPER))));
        RuntimeAccessControlService access = new RuntimeAccessControlService(
                new LogAccessControlService(logProperties), properties);
        return new RuntimeQueryService(properties, catalog, registry, access,
                new RuntimeCache(properties), new RuntimeSnapshotStore(),
                new RuntimeAuditService(new AuditLogService(), MAPPER),
                new LogCircuitBreaker(), new LogExecutorRegistry(logProperties),
                new LogMaskingService(logProperties));
    }

    private RuntimeProperties.Source sourceConfig(String baseUrl) {
        return RuntimeTestSupport.sourceConfig("flink-default", "flink", "live", baseUrl);
    }

    private RuntimeQueryDTO dto(String jobId, String family) {
        RuntimeQueryDTO dto = new RuntimeQueryDTO();
        dto.setSourceId("flink-default");
        dto.setJobId(jobId);
        dto.setFamilies(List.of(family));
        dto.setIncludeComponents(true);
        dto.setIncludeVertices(false);
        dto.setIncludeConfig(false);
        return dto;
    }

    private String jobJson() {
        return """
                {"jid":"j1","name":"demo","state":"RUNNING","start-time":1700000000000,
                 "end-time":-1,"duration":60000,"tasks":{"total":2,"failed":0},
                 "vertices":[{"id":"v1","name":"Source","status":"RUNNING","tasks":{"failed":0}}]}
                """;
    }

    private String taskManagersJson() {
        return """
                {"taskmanagers":[{"id":"tm-1","path":"akka.tcp://flink@host1:1234/user/taskmanager",
                  "timeSinceLastHeartbeat":1000,"slotsNumber":2,"freeSlots":1,
                  "totalResource":{"cpuCores":2,"memory":1073741824,"managedMemory":536870912}}]}
                """;
    }

    private static final class FakeHttp implements HttpLogClient {
        private final Map<String, HttpTextResponse> responses = new HashMap<>();

        private void on(String suffix, int status, String body) {
            responses.put(suffix, new HttpTextResponse(status, body, "application/json"));
        }

        @Override
        public HttpTextResponse get(String url, Map<String, String> headers, Duration timeout) {
            String matched = null;
            for (String suffix : responses.keySet()) {
                if (url.contains(suffix) && (matched == null || suffix.length() > matched.length())) {
                    matched = suffix;
                }
            }
            return matched == null ? new HttpTextResponse(404, "{}", "application/json")
                    : responses.get(matched);
        }
    }
}
