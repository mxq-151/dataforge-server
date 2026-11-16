package com.bigdata.modules.telemetry.service.logdata;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.util.CircuitBreaker;
import com.bigdata.modules.telemetry.service.catalog.spi.CatalogContributor;
import com.bigdata.modules.telemetry.service.metricdata.catalog.FlinkMetricSemantics;
import com.bigdata.modules.telemetry.service.metricdata.catalog.RestMetricSemanticsRegistry;
import com.bigdata.modules.telemetry.service.metricdata.catalog.SparkShsMetricSemantics;
import com.bigdata.modules.telemetry.service.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.dto.ExceptionQueryRequest;
import com.bigdata.modules.telemetry.common.model.BackendStatus;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.vo.CatalogStatus;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.routing.BackendRouter;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.vo.ExceptionData;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry.EngineEvidenceDefinition;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.spi.ExceptionSummaryBackend;

/**
 * 异常摘要编排测试：空结果、截断、不可用与未声明四种边界。
 *
 * <p>Flink 2.2 只有列表接口；这里不构造单条详情场景。</p>
 */
class ExceptionTelemetryQueryServiceTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("空异常历史：ok + empty，明确不是取不到")
    void emptyHistoryIsOk() {
        FakeBackend backend = new FakeBackend(emptyResult());

        TelemetryQueryResponse response = service(backend, true).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.error()).isNull();
        ExceptionData data =
                (ExceptionData) response.data();
        assertThat(data.empty()).isTrue();
        assertThat(data.resultState()).isEqualTo("empty");
        assertThat(data.exceptions()).isEmpty();
        assertThat(data.truncated()).isFalse();
        assertThat(data.warnings())
                .anyMatch(warning -> warning.code().equals("NO_EXCEPTION_HISTORY"));
        assertThat(response.meta().truncated()).isFalse();
    }

    @Test
    @DisplayName("有异常且触顶：ok + truncated + RESULT_TRUNCATED")
    void truncatedResultKeepsCode() {
        ExceptionQueryResult.ExceptionRecord record = new ExceptionQueryResult.ExceptionRecord(
                "java.lang.RuntimeException", "java.io.IOException: disk",
                "java.lang.RuntimeException: boom", Instant.ofEpochMilli(1794756213241L),
                "task-1", "tm-1", "tm-1:1234", Map.of(), false, false, 26, List.of());
        ExceptionQueryResult result = new ExceptionQueryResult(List.of(record), 0,
                false, true, 1, List.of());
        FakeBackend backend = new FakeBackend(result);

        TelemetryQueryResponse response = service(backend, true).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        ExceptionData data =
                (ExceptionData) response.data();
        assertThat(data.resultState()).isEqualTo("partial");
        assertThat(data.truncated()).isTrue();
        assertThat(data.truncatedCode()).isEqualTo(CapabilityCode.RESULT_TRUNCATED);
        assertThat(data.exceptions()).singleElement().satisfies(entry -> {
            assertThat(entry.exceptionName()).isEqualTo("java.lang.RuntimeException");
            assertThat(entry.rootCause()).isEqualTo("java.io.IOException: disk");
            assertThat(entry.taskName()).isEqualTo("task-1");
        });
        assertThat(response.meta().truncated()).isTrue();
    }

    @Test
    @DisplayName("404：failed + NOT_FOUND，不返回空数组")
    void notFoundIsFailed() {
        FakeBackend backend = new FakeBackend(null);
        backend.failure = new BackendException(CapabilityCode.NOT_FOUND, "job not found");

        TelemetryQueryResponse response = service(backend, true).query(request());

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.NOT_FOUND);
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("上游不可用：unavailable + code + requirements")
    void upstreamFailureIsUnavailable() {
        FakeBackend backend = new FakeBackend(null);
        backend.failure = new BackendException(CapabilityCode.UPSTREAM_ERROR, "flink down");

        TelemetryQueryResponse response = service(backend, true).query(request());

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.retryable()).isTrue();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.UPSTREAM_ERROR);
        assertThat(response.error().requirements())
                .anyMatch(requirement -> requirement.key().contains("flink-rest"));
    }

    @Test
    @DisplayName("缺少 ref.jobId：400，不发起后端调用")
    void missingJobId() {
        FakeBackend backend = new FakeBackend(emptyResult());
        ExceptionQueryRequest request = request();
        request.setRef(Map.of());

        assertThatThrownBy(() -> service(backend, true).query(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getMessage())
                        .contains("ref.jobId"));
    }

    @Test
    @DisplayName("组件未声明 exception_summary：not_implemented，不伪造数据")
    void missingEvidence() {
        FakeBackend backend = new FakeBackend(emptyResult());

        TelemetryQueryResponse response = service(backend, false).query(request());

        assertThat(response.outcome()).isEqualTo("not_implemented");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED);
    }

    // ------------------------------------------------------------------ 辅助

    private ExceptionQueryRequest request() {
        ExceptionQueryRequest request = new ExceptionQueryRequest();
        request.setTarget(TARGET);
        request.setRef(Map.of("jobId", "j1"));
        return request;
    }

    private ExceptionQueryResult emptyResult() {
        return new ExceptionQueryResult(List.of(), 0, false, false, 20, List.of());
    }

    private ExceptionTelemetryQueryService service(FakeBackend backend, boolean declareEvidence) {
        TelemetryBackendRegistry registry = new TelemetryBackendRegistry(
                List.of(backend), List.of(), List.of(backend));
        BackendRouter router = new BackendRouter(new RoutingProperties(), registry);
        List<EngineEvidenceDefinition> evidence = declareEvidence
                ? List.of(new EngineEvidenceDefinition("exception_summary", List.of(binding())))
                : List.of();
        ComponentCatalogEntry entry = new ComponentCatalogEntry("flink", "flink.jobmanager",
                List.of(), List.of(), evidence, CatalogStatus.IMPLEMENTED, List.of());
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of((CatalogContributor) () -> List.of(entry)),
                new RestMetricSemanticsRegistry(new FlinkMetricSemantics(), new SparkShsMetricSemantics()));
        return new ExceptionTelemetryQueryService(catalog, router, registry,
                new CircuitBreaker());
    }

    private BackendBinding binding() {
        return new BackendBinding("flink-rest", BackendType.FLINK_REST, BackendStatus.IMPLEMENTED,
                10, Set.of(QueryMode.INSTANT), Set.of(), true, null, null);
    }

    private static final class FakeBackend implements MetricBackend, ExceptionSummaryBackend {

        private final ExceptionQueryResult result;
        private BackendException failure;

        private FakeBackend(ExceptionQueryResult result) {
            this.result = result;
        }

        @Override
        public String backendId() {
            return "flink-rest";
        }

        @Override
        public BackendType backendType() {
            return BackendType.FLINK_REST;
        }

        @Override
        public BackendCapability evaluate(MetricQuerySpec spec) {
            return BackendCapability.available();
        }

        @Override
        public MetricQueryResult fetch(MetricQuerySpec spec) {
            return new MetricQueryResult(List.of(), false, List.of());
        }

        @Override
        public BackendCapability evaluate(ExceptionQuerySpec spec) {
            return BackendCapability.available();
        }

        @Override
        public ExceptionQueryResult fetch(ExceptionQuerySpec spec) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
