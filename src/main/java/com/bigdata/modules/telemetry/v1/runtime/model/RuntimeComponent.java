package com.bigdata.modules.telemetry.v1.runtime.model;

import java.util.List;

/**
 * 运行时组件：Flink TaskManager / vertex，Spark executor / stage。
 */
public record RuntimeComponent(String id,
                               String type,
                               String name,
                               String host,
                               String status,
                               Integer slots,
                               Integer freeSlots,
                               Integer cores,
                               Long maxMemoryBytes,
                               Long usedMemoryBytes,
                               Long startTime,
                               Long endTime,
                               String note,
                               List<RuntimeMetric> metrics) {

    public RuntimeComponent withMetrics(List<RuntimeMetric> value) {
        return new RuntimeComponent(id, type, name, host, status, slots, freeSlots, cores,
                maxMemoryBytes, usedMemoryBytes, startTime, endTime, note, value);
    }
}
