package com.bigdata.modules.telemetry.v1.runtime.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.v1.catalog.SeedCatalogContributor;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.dto.CompareV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.DiscoveryQueryV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.SnapshotV1Request;
import com.bigdata.modules.telemetry.v1.runtime.engine.RuntimeEngineRegistry;
import com.bigdata.modules.telemetry.v1.runtime.model.CompareData;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.DiscoveryData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeMetric;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeEngine;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeQuerySpec;
import com.bigdata.modules.telemetry.v1.runtime.support.SensitiveDataMasker;
import com.bigdata.modules.telemetry.v1.runtime.support.SnapshotHistory;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeTelemetryQueryServiceTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    private FakeEngine engine;
    private RuntimeTelemetryQueryService service;

    @BeforeEach
    void setUp() {
        engine = new FakeEngine();
        RuntimeEngineRegistry registry = mock(RuntimeEngineRegistry.class);
        when(registry.resolve("flink", "live")).thenReturn(Optional.of(engine));
        when(registry.enginesFor("flink")).thenReturn(List.of(engine));
        RuntimeV1Properties properties = new RuntimeV1Properties();
        service = new RuntimeTelemetryQueryService(registry,
                new TelemetryCatalogRegistry(List.of(new SeedCatalogContributor())),
                properties, new SnapshotHistory(properties), new SensitiveDataMasker());
    }

    @Test
    @DisplayName("snapshot：缺 jobId 返回参数错误")
    void snapshotRequiresJobId() {
        SnapshotV1Request request = new SnapshotV1Request();
        request.setTarget(TARGET);

        assertThatThrownBy(() -> service.snapshot(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("jobId");
    }

    @Test
    @DisplayName("snapshot：配置参数脱敏，指标原样返回")
    void snapshotMasksConfig() {
        SnapshotV1Request request = new SnapshotV1Request();
        request.setTarget(TARGET);
        request.setSourceMode("live");
        request.setJobId("j1");
        request.setIncludeConfig(true);

        TelemetryQueryV1Response response = service.snapshot(request);

        assertThat(response.outcome()).isEqualTo("ok");
        SnapshotData data = (SnapshotData) response.data();
        assertThat(data.jobMetrics()).singleElement()
                .satisfies(metric -> assertThat(metric.value()).isEqualTo(10.0));
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) data.jobConfig().get("job");
        assertThat(job).containsEntry("sasl.jaas.config", "***")
                .containsEntry("parallelism.default", "2");
    }

    @Test
    @DisplayName("compare：首次无历史 → SAMPLE_INSUFFICIENT")
    void compareWithoutHistory() {
        CompareV1Request request = compareRequest("j1", 60L);

        CompareData data = (CompareData) service.compare(request).data();

        assertThat(data.comparable()).isFalse();
        assertThat(data.reason())
                .isEqualTo(RuntimeTelemetryQueryService.REASON_SAMPLE_INSUFFICIENT);
    }

    @Test
    @DisplayName("compare：同作业窗口内可比，输出增量与速率")
    void compareProducesDelta() {
        service.compare(compareRequest("j1", 60L));
        engine.capturedAt = 5000;
        engine.value = 30;

        CompareData data = (CompareData) service.compare(compareRequest("j1", 60L)).data();

        assertThat(data.comparable()).isTrue();
        assertThat(data.reason()).isNull();
        assertThat(data.rows()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("flink_records_in_total");
            assertThat(row.from()).isEqualTo(10.0);
            assertThat(row.to()).isEqualTo(30.0);
            assertThat(row.delta()).isEqualTo(20.0);
            assertThat(row.perSecond()).isEqualTo(5.0);
        });
    }

    @Test
    @DisplayName("compare：超出时间窗 → TIME_WINDOW_EXCEEDED")
    void compareWindowExceeded() {
        service.compare(compareRequest("j1", 60L));
        engine.capturedAt = 120_000;

        CompareData data = (CompareData) service.compare(compareRequest("j1", 60L)).data();

        assertThat(data.comparable()).isFalse();
        assertThat(data.reason())
                .isEqualTo(RuntimeTelemetryQueryService.REASON_TIME_WINDOW_EXCEEDED);
    }

    @Test
    @DisplayName("compare：同 target 只有别的作业 → JOB_MISMATCH")
    void compareJobMismatch() {
        service.compare(compareRequest("j1", 60L));
        engine.capturedAt = 2000;

        CompareData data = (CompareData) service.compare(compareRequest("j2", 60L)).data();

        assertThat(data.comparable()).isFalse();
        assertThat(data.reason()).isEqualTo(RuntimeTelemetryQueryService.REASON_JOB_MISMATCH);
    }

    @Test
    @DisplayName("discovery sources：返回已配置引擎与支持/不支持的指标族")
    void discoverySources() {
        DiscoveryQueryV1Request request = new DiscoveryQueryV1Request();
        request.setScope("sources");
        request.setTarget(TARGET);

        DiscoveryData data = (DiscoveryData) service.discovery(request).data();

        assertThat(data.scope()).isEqualTo("sources");
        assertThat(data.sources()).singleElement().satisfies(source -> {
            assertThat(source.backendId()).isEqualTo("flink-rest");
            assertThat(source.supportedFamilies()).contains("checkpoint", "throughput");
        });
    }

    @Test
    @DisplayName("discovery scope 非法 → 参数错误")
    void discoveryRejectsUnknownScope() {
        DiscoveryQueryV1Request request = new DiscoveryQueryV1Request();
        request.setScope("unknown");
        request.setTarget(TARGET);

        assertThatThrownBy(() -> service.discovery(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scope");
    }

    private CompareV1Request compareRequest(String jobId, Long windowSeconds) {
        CompareV1Request request = new CompareV1Request();
        request.setTarget(TARGET);
        request.setSourceMode("live");
        request.setJobId(jobId);
        request.setWindowSeconds(windowSeconds);
        return request;
    }

    /** 可调控时间与指标值的假引擎。 */
    private static final class FakeEngine implements RuntimeEngine {

        private long capturedAt = 1000;
        private double value = 10;

        @Override
        public String backendId() {
            return "flink-rest";
        }

        @Override
        public BackendType backendType() {
            return BackendType.FLINK_REST;
        }

        @Override
        public String serviceType() {
            return "flink";
        }

        @Override
        public String mode() {
            return "live";
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String unconfiguredReason() {
            return null;
        }

        @Override
        public List<DiscoveryData.JobView> jobs(RuntimeQuerySpec spec) {
            return List.of(new DiscoveryData.JobView("j1", "demo", "RUNNING", 1L, null, null,
                    null, null));
        }

        @Override
        public List<RuntimeComponent> components(RuntimeQuerySpec spec) {
            return List.of();
        }

        @Override
        public SnapshotData snapshot(RuntimeQuerySpec spec) {
            RuntimeMetric metric = RuntimeMetric.available("flink_records_in_total",
                    "throughput", "numRecordsIn", value, "records");
            return new SnapshotData(spec.jobId(), "demo", "RUNNING", 1L, null, null, capturedAt,
                    List.of(), List.of(metric), null, Map.of(),
                    Map.of("job", Map.of("sasl.jaas.config", "password=\"x\"",
                            "parallelism.default", "2")), List.of());
        }

        @Override
        public ConfigData config(RuntimeQuerySpec spec) {
            return new ConfigData(true, null, spec.jobId(), capturedAt, Map.of(), List.of(),
                    List.of());
        }
    }
}
