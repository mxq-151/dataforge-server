package com.bigdata.modules.telemetry.v1.runtime.model;

import java.util.List;
import java.util.Map;

/** 单作业运行时快照；取不到的指标族放进 {@code unavailableFamilies}。 */
public record SnapshotData(String jobId,
                           String jobName,
                           String state,
                           Long startTime,
                           Long endTime,
                           Long durationMs,
                           long capturedAt,
                           List<RuntimeComponent> components,
                           List<RuntimeMetric> jobMetrics,
                           CheckpointView checkpoint,
                           Map<String, String> unavailableFamilies,
                           Map<String, Object> jobConfig,
                           List<String> warnings) {

    public record CheckpointView(boolean available,
                                 String reason,
                                 Integer completed,
                                 Integer failed,
                                 Integer inProgress,
                                 Integer restored,
                                 String latestFailedReason,
                                 List<Entry> history) {

        public record Entry(Long id,
                            String status,
                            Long triggerTimestamp,
                            Long completedTimestamp,
                            Long durationMs,
                            Long sizeBytes,
                            String failureMessage) {
        }
    }
}
