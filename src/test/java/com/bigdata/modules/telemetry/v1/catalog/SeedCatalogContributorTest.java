package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.BackendBinding;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCatalogContributorTest {

    private final List<ComponentCatalogEntry> identityEntries =
            new SeedCatalogContributor().contribute();
    private final TelemetryCatalogRegistry merged = new TelemetryCatalogRegistry(
            List.of(new SeedCatalogContributor()),
            new RestMetricSemanticsRegistry(new FlinkMetricSemantics()));
    private final List<ComponentCatalogEntry> entries = List.copyOf(merged.catalog().entries());

    @Test
    @DisplayName("身份目录不再包含任何指标物理名/类型/单位")
    void identityCatalogHasNoMetricSemantics() {
        assertThat(identityEntries).allSatisfy(entry -> assertThat(entry.metrics()).isEmpty());
    }

    @Test
    @DisplayName("log.service 只挂 local-disk / ES，log.task 只挂 yarn")
    void dataTypesHaveDisjointBackends() {
        for (ComponentCatalogEntry entry : entries) {
            for (LogStreamDefinition stream : entry.logStreams()) {
                for (BackendBinding binding : stream.supportedBackends()) {
                    String as = String.format("%s/%s/%s/%s", entry.serviceType(),
                            entry.componentId(), stream.dataType().wire(), stream.stream());
                    if (stream.dataType() == LogDataType.SERVICE) {
                        if (binding.backendType() == BackendType.DORIS_AUDIT) {
                            assertThat(entry.serviceType()).as(as).isEqualTo("doris");
                            assertThat(stream.stream()).as(as).isEqualTo("slowquery");
                        } else {
                            assertThat(binding.backendType()).as(as)
                                    .isIn(BackendType.LOCAL_DISK, BackendType.ELASTICSEARCH);
                        }
                    } else if ("ds".equals(entry.serviceType())) {
                        assertThat(binding.backendType()).as(as).isEqualTo(BackendType.DS_API);
                    } else {
                        assertThat(binding.backendType()).as(as).isEqualTo(BackendType.YARN);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("slowquery 只在 log.service 下声明，log.task 不得出现")
    void slowQueryOnlyOnServiceLogs() {
        for (ComponentCatalogEntry entry : entries) {
            for (LogStreamDefinition stream : entry.logStreams()) {
                if ("slowquery".equals(stream.stream())) {
                    assertThat(stream.dataType()).isEqualTo(LogDataType.SERVICE);
                }
            }
        }
    }

    @Test
    @DisplayName("两类日志的等价组不相交，禁止互为回退")
    void equivalenceGroupsDoNotCrossDataTypes() {
        Set<String> serviceGroups = new HashSet<>();
        Set<String> taskGroups = new HashSet<>();
        for (ComponentCatalogEntry entry : entries) {
            for (LogStreamDefinition stream : entry.logStreams()) {
                for (BackendBinding binding : stream.supportedBackends()) {
                    if (binding.equivalenceGroup() == null) {
                        continue;
                    }
                    if (stream.dataType() == LogDataType.SERVICE) {
                        serviceGroups.add(binding.equivalenceGroup());
                    } else {
                        taskGroups.add(binding.equivalenceGroup());
                    }
                }
            }
        }
        assertThat(serviceGroups).isNotEmpty();
        assertThat(taskGroups).isEmpty();
    }

    @Test
    @DisplayName("log.task 要求 taskId / jobName 至少一个")
    void taskStreamsRequireTaskReference() {
        for (ComponentCatalogEntry entry : entries) {
            for (LogStreamDefinition stream : entry.logStreams()) {
                if (stream.dataType() == LogDataType.TASK) {
                    List<String> expected = "ds".equals(entry.serviceType())
                            ? List.of("jobId", "jobName")
                            : List.of("taskId", "jobName");
                    assertThat(stream.requiredAnyRefs())
                            .as("%s/%s", entry.componentId(), stream.stream())
                            .containsExactlyInAnyOrderElementsOf(expected);
                    assertThat(stream.requirements()).isNotEmpty();
                }
            }
        }
    }

    @Test
    @DisplayName("ds.worker 只声明 log.task，绑定 ds-api，不声明指标 / 引擎证据")
    void dsTaskLogsUseDsApi() {
        ComponentCatalogEntry ds = entry("ds", "ds.worker");

        assertThat(ds.metrics()).isEmpty();
        assertThat(ds.engineEvidence()).isEmpty();
        assertThat(ds.logStreams()).hasSize(2).allSatisfy(stream -> {
            assertThat(stream.dataType()).isEqualTo(LogDataType.TASK);
            assertThat(stream.supportedBackends()).singleElement().satisfies(binding -> {
                assertThat(binding.backendType()).isEqualTo(BackendType.DS_API);
                assertThat(binding.status()).isEqualTo(BackendStatus.IMPLEMENTED);
            });
            assertThat(stream.requiredAnyRefs()).containsExactlyInAnyOrder("jobId", "jobName");
        });
    }

    @Test
    @DisplayName("Flink checkpoint 走 REST 事件历史，背压只声明 instant")
    void flinkRestCapabilities() {
        ComponentCatalogEntry jobManager = entry("flink", "flink.jobmanager");
        MetricDefinition checkpoint = metric(jobManager, "flink_checkpoint_duration_seconds");
        BackendBinding checkpointRest = binding(checkpoint, BackendType.FLINK_REST);
        assertThat(checkpointRest.status()).isEqualTo(BackendStatus.IMPLEMENTED);
        assertThat(checkpointRest.queryModes()).contains(QueryMode.RANGE, QueryMode.INSTANT);

        ComponentCatalogEntry taskManager = entry("flink", "flink.taskmanager");
        MetricDefinition backpressure = metric(taskManager, "flink_backpressure_busy_ratio");
        BackendBinding backpressureRest = binding(backpressure, BackendType.FLINK_REST);
        assertThat(backpressureRest.status()).isEqualTo(BackendStatus.IMPLEMENTED);
        assertThat(backpressureRest.queryModes()).containsExactly(QueryMode.INSTANT);

        assertThat(jobManager.engineEvidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.supportedBackends())
                        .singleElement()
                        .satisfies(rest -> assertThat(rest.status()).isEqualTo(BackendStatus.IMPLEMENTED)));
    }

    @Test
    @DisplayName("Spark 不再有手工指标语义，仅声明作业级指标缺口")
    void sparkHasNoMetricSemanticsButDeclaresGap() {
        for (String componentId : List.of("spark.driver", "spark.executor", "spark.shs")) {
            ComponentCatalogEntry entry = entry("spark", componentId);
            assertThat(entry.metrics()).as(componentId).isEmpty();
            assertThat(entry.metricRequirements()).as(componentId)
                    .singleElement()
                    .satisfies(requirement -> {
                        assertThat(requirement.key()).isEqualTo("spark.job.metrics");
                        assertThat(requirement.description()).contains("SHS 只有事件日志");
                    });
        }
    }

    @Test
    @DisplayName("Flink 指标带真实 raw 名与端点，新增族非空")
    void engineRawMetricsAreDeclared() {
        for (ComponentCatalogEntry entry : entries) {
            if (!"flink".equals(entry.serviceType())) {
                continue;
            }
            for (MetricDefinition metric : entry.metrics()) {
                assertThat(metric.rawMetric()).as("%s/%s", entry.componentId(), metric.name())
                        .isNotBlank();
                assertThat(metric.endpoint()).as("%s/%s", entry.componentId(), metric.name())
                        .isNotBlank();
                assertThat(metric.verified()).as("%s/%s", entry.componentId(), metric.name())
                        .isNotNull();
            }
        }

        ComponentCatalogEntry jobManager = entry("flink", "flink.jobmanager");
        assertThat(metric(jobManager, "flink_checkpoint_duration_seconds").rawMetric())
                .isEqualTo("lastCheckpointDuration");
        assertThat(metric(jobManager, "flink_tasks_failed").endpoint()).isEqualTo("/jobs/{id}");

        ComponentCatalogEntry taskManager = entry("flink", "flink.taskmanager");
        assertThat(metric(taskManager, "flink_shuffle_bytes_in_remote_total").rawMetric())
                .isEqualTo("numBytesInRemote");
        assertThat(metric(taskManager, "flink_vertex_records_in_rate").rawMetric())
                .isEqualTo("numRecordsInPerSecond");
    }

    @Test
    @DisplayName("Doris slowquery：审计表优先，fe.audit.log 走 local-disk 退路，同组才可回退")
    void dorisSlowQueryUsesAuditWithLocalDiskFallback() {
        for (String componentId : List.of("doris.fe", "doris.be")) {
            ComponentCatalogEntry doris = entry("doris", componentId);
            LogStreamDefinition slowQuery = doris.logStreams().stream()
                    .filter(stream -> "slowquery".equals(stream.stream()))
                    .findFirst()
                    .orElseThrow();

            assertThat(slowQuery.dataType()).isEqualTo(LogDataType.SERVICE);
            assertThat(slowQuery.supportedBackends())
                    .extracting(BackendBinding::backendType)
                    .containsExactly(BackendType.DORIS_AUDIT, BackendType.LOCAL_DISK);
            assertThat(slowQuery.supportedBackends())
                    .allSatisfy(binding -> {
                        assertThat(binding.status()).isEqualTo(BackendStatus.IMPLEMENTED);
                        assertThat(binding.equivalenceGroup()).isEqualTo("doris-slowquery");
                    });
            assertThat(slowQuery.supportedBackends().get(0).priority())
                    .isLessThan(slowQuery.supportedBackends().get(1).priority());
            assertThat(slowQuery.requirements()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("非 Doris 的 slowquery 仍为声明未实现")
    void otherSlowQueryStaysDeclared() {
        for (String service : List.of("starrocks", "clickhouse")) {
            for (ComponentCatalogEntry entry : entries) {
                if (!service.equals(entry.serviceType())) {
                    continue;
                }
                entry.logStreams().stream()
                        .filter(stream -> "slowquery".equals(stream.stream()))
                        .forEach(stream -> assertThat(stream.supportedBackends())
                                .allSatisfy(binding -> assertThat(binding.status())
                                        .isEqualTo(BackendStatus.DECLARED_NOT_IMPLEMENTED)));
            }
        }
    }

    // ------------------------------------------------------------------ 辅助

    private ComponentCatalogEntry entry(String serviceType, String componentId) {
        return entries.stream()
                .filter(entry -> entry.serviceType().equals(serviceType)
                        && entry.componentId().equals(componentId))
                .findFirst()
                .orElseThrow();
    }

    private MetricDefinition metric(ComponentCatalogEntry entry, String name) {
        return entry.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private BackendBinding binding(MetricDefinition metric, BackendType type) {
        return metric.supportedBackends().stream()
                .filter(binding -> binding.backendType() == type)
                .findFirst()
                .orElseThrow();
    }
}
