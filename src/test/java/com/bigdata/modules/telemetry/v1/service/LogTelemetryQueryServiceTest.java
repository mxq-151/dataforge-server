package com.bigdata.modules.telemetry.v1.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.shared.LogCircuitBreaker;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.catalog.CatalogContributor;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.dto.LogQueryV1Request;
import com.bigdata.modules.telemetry.v1.model.BackendBinding;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.CatalogStatus;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogEntry;
import com.bigdata.modules.telemetry.v1.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.routing.BackendRouter;
import com.bigdata.modules.telemetry.v1.routing.RoutingProperties;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.LogBackend;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogTelemetryQueryServiceTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("log.task 缺 taskId / jobName：400 并指出两个字段")
    void taskMissingReferenceRejected() {
        assertThatThrownBy(() -> service(List.of(serviceStream(), taskStream())).query(
                request("log.task", "stdout", Map.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(400);
                    assertThat(business.getMessage())
                            .contains("ref.taskId").contains("ref.jobName").contains("taskId 为准");
                });
    }

    @Test
    @DisplayName("log.task 只选 YARN，服务日志后端不被调用")
    void taskRoutesToYarnOnly() {
        FakeLogBackend localDisk = new FakeLogBackend("local-disk", BackendType.LOCAL_DISK);
        FakeLogBackend yarn = new FakeLogBackend("yarn", BackendType.YARN);
        TelemetryQueryV1Response response = service(localDisk, yarn, List.of(serviceStream(), taskStream())).query(
                request("log.task", "stdout", Map.of("taskId", "application_1")));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("yarn");
        assertThat(localDisk.fetches).isZero();
        TelemetryQueryV1Response.LogData data = (TelemetryQueryV1Response.LogData) response.data();
        assertThat(data.dataType()).isEqualTo("log.task");
    }

    @Test
    @DisplayName("只给 jobName 也接受（多选一引用）")
    void taskAcceptsJobNameOnly() {
        FakeLogBackend yarn = new FakeLogBackend("yarn", BackendType.YARN);
        TelemetryQueryV1Response response = service(yarn, List.of(serviceStream(), taskStream()))
                .query(request("log.task", "stdout", Map.of("jobName", "daily_etl")));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("yarn");
    }

    @Test
    @DisplayName("log.service 定义里混入 yarn 绑定：跳过并标记 LOG_DATA_TYPE_MISMATCH")
    void serviceLogsNeverUseYarn() {
        FakeLogBackend localDisk = new FakeLogBackend("local-disk", BackendType.LOCAL_DISK);
        FakeLogBackend yarn = new FakeLogBackend("yarn", BackendType.YARN);
        LogStreamDefinition poisoned = new LogStreamDefinition(LogDataType.SERVICE, "stdout",
                List.of(), List.of(),
                List.of(yarn(1), localDisk(10, "flink-logs")), List.of());

        TelemetryQueryV1Response response = service(localDisk, yarn, List.of(poisoned))
                .query(request("log.service", "stdout", Map.of()));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("local-disk");
        assertThat(response.backend().skipped()).extracting(skip -> skip.code())
                .contains(CapabilityCode.LOG_DATA_TYPE_MISMATCH);
        assertThat(yarn.fetches).isZero();
    }

    @Test
    @DisplayName("log.task + slowquery：明确返回 STREAM_NOT_SUPPORTED，不返回空数组")
    void slowQueryNotAvailableForTaskLogs() {
        TelemetryQueryV1Response response = service(List.of(serviceStream(), taskStream()))
                .query(request("log.task", "slowquery", Map.of("taskId", "application_1")));

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.STREAM_NOT_SUPPORTED);
        assertThat(response.error().message()).contains("slowquery");
    }

    @Test
    @DisplayName("stream 只在另一个 dataType 下声明：STREAM_NOT_SUPPORTED")
    void streamDeclaredUnderOtherDataType() {
        TelemetryQueryV1Response response = service(List.of(serviceStream())).query(
                request("log.task", "stdout", Map.of("taskId", "application_1")));

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.STREAM_NOT_SUPPORTED);
        assertThat(response.error().message()).contains("log.service");
    }

    @Test
    @DisplayName("缺 dataType / 未知 dataType：400")
    void dataTypeValidated() {
        LogTelemetryQueryService service = service(List.of(serviceStream(), taskStream()));

        assertThatThrownBy(() -> service.query(request(null, "stdout", Map.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(400));

        assertThatThrownBy(() -> service.query(request("log.audit", "stdout", Map.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getMessage())
                        .contains("log.audit"));
    }

    // ------------------------------------------------------------------ 辅助

    private LogTelemetryQueryService service(List<LogStreamDefinition> streams) {
        return service(null, null, streams);
    }

    private LogTelemetryQueryService service(FakeLogBackend serviceBackend,
                                             List<LogStreamDefinition> streams) {
        return service(serviceBackend, null, streams);
    }

    private LogTelemetryQueryService service(FakeLogBackend first, FakeLogBackend second,
                                             List<LogStreamDefinition> streams) {
        List<LogBackend> backends = new ArrayList<>();
        if (first != null) {
            backends.add(first);
        }
        if (second != null) {
            backends.add(second);
        }
        TelemetryBackendRegistry registry = new TelemetryBackendRegistry(List.of(), backends);
        RoutingProperties routing = new RoutingProperties();
        BackendRouter router = new BackendRouter(routing, registry);
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of((CatalogContributor) () -> List.of(new ComponentCatalogEntry(
                        TARGET.serviceType(), TARGET.componentId(), List.of(), streams, List.of(),
                        CatalogStatus.IMPLEMENTED, List.of()))));
        return new LogTelemetryQueryService(catalog, router, registry, new LogCircuitBreaker());
    }

    private LogStreamDefinition serviceStream() {
        return new LogStreamDefinition(LogDataType.SERVICE, "stdout", List.of(), List.of(),
                List.of(localDisk(10, "flink-logs")), List.of());
    }

    private LogStreamDefinition taskStream() {
        return new LogStreamDefinition(LogDataType.TASK, "stdout", List.of(),
                List.of("taskId", "jobName"), List.of(yarn(10)), List.of());
    }

    private BackendBinding localDisk(int priority, String group) {
        return new BackendBinding("local-disk", BackendType.LOCAL_DISK, BackendStatus.IMPLEMENTED,
                priority, java.util.Set.of(), java.util.Set.of(), false, group, null);
    }

    private BackendBinding yarn(int priority) {
        return new BackendBinding("yarn", BackendType.YARN, BackendStatus.IMPLEMENTED, priority,
                java.util.Set.of(), java.util.Set.of(), false, null, null);
    }

    private LogQueryV1Request request(String dataType, String stream, Map<String, String> refs) {
        LogQueryV1Request request = new LogQueryV1Request();
        request.setMode("data");
        request.setTarget(TARGET);
        request.setDataType(dataType);
        request.setStream(stream);
        request.setRef(refs);
        return request;
    }

    private static final class FakeLogBackend implements LogBackend {

        private final String id;
        private final BackendType type;
        private int fetches;

        private FakeLogBackend(String id, BackendType type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public BackendType backendType() {
            return type;
        }

        @Override
        public BackendCapability evaluate(LogQuerySpec spec) {
            return BackendCapability.available();
        }

        @Override
        public LogQueryResult fetch(LogQuerySpec spec) {
            fetches++;
            return new LogQueryResult(List.of(new LogEntry(null, spec.stream(),
                    spec.target().hostId(), "line", Map.of())), null, false, null, List.of());
        }
    }
}
