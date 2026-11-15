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

    private final List<ComponentCatalogEntry> entries = new SeedCatalogContributor().contribute();

    @Test
    @DisplayName("log.service 只挂 local-disk / ES，log.task 只挂 yarn")
    void dataTypesHaveDisjointBackends() {
        for (ComponentCatalogEntry entry : entries) {
            for (LogStreamDefinition stream : entry.logStreams()) {
                for (BackendBinding binding : stream.supportedBackends()) {
                    String as = String.format("%s/%s/%s/%s", entry.serviceType(),
                            entry.componentId(), stream.dataType().wire(), stream.stream());
                    if (stream.dataType() == LogDataType.SERVICE) {
                        assertThat(binding.backendType()).as(as)
                                .isIn(BackendType.LOCAL_DISK, BackendType.ELASTICSEARCH);
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
                    assertThat(stream.requiredAnyRefs())
                            .as("%s/%s", entry.componentId(), stream.stream())
                            .containsExactlyInAnyOrder("taskId", "jobName");
                    assertThat(stream.requirements()).isNotEmpty();
                }
            }
        }
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
    @DisplayName("Spark stage/executor 快照已实现，只声明 instant")
    void sparkRestCapabilities() {
        MetricDefinition gc = metric(entry("spark", "spark.executor"),
                "spark_executor_gc_seconds_total");
        BackendBinding rest = binding(gc, BackendType.SPARK_REST);

        assertThat(rest.status()).isEqualTo(BackendStatus.IMPLEMENTED);
        assertThat(rest.queryModes()).containsExactly(QueryMode.INSTANT);
    }

    @Test
    @DisplayName("Flink/Spark 指标带真实 raw 名与端点，新增族非空")
    void engineRawMetricsAreDeclared() {
        for (ComponentCatalogEntry entry : entries) {
            if (!"flink".equals(entry.serviceType()) && !"spark".equals(entry.serviceType())) {
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

        ComponentCatalogEntry driver = entry("spark", "spark.driver");
        assertThat(metric(driver, "spark_application_attempt_count").rawMetric())
                .isEqualTo("attempts");
        assertThat(metric(driver, "spark_stage_attempts").note()).contains("attemptId");
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
