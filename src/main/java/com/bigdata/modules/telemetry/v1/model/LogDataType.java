package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 日志数据分类：服务自身日志与在服务上运行的任务日志。
 *
 * <p>两类日志的证据来源不同，后端集合严格隔离：服务日志只能走本地磁盘 / Elasticsearch，
 * 任务日志走 YARN 聚合日志或 DolphinScheduler 任务日志。两者之间不允许回退，否则会拿服务日志
 * 冒充任务日志。</p>
 */
public enum LogDataType {

    SERVICE("log.service",
            Set.of(BackendType.LOCAL_DISK, BackendType.ELASTICSEARCH, BackendType.DORIS_AUDIT),
            Set.of("stdout", "stderr", "slowquery")),

    TASK("log.task",
            Set.of(BackendType.YARN, BackendType.DS_API),
            Set.of("stdout", "stderr"));

    private final String wire;
    private final Set<BackendType> backends;
    private final Set<String> streams;

    LogDataType(String wire, Set<BackendType> backends, Set<String> streams) {
        this.wire = wire;
        this.backends = backends;
        this.streams = streams;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public boolean allowsBackend(BackendType type) {
        return type != null && backends.contains(type);
    }

    public boolean supportsStream(String stream) {
        return stream != null && streams.contains(stream);
    }

    @JsonCreator
    public static LogDataType fromWire(String value) {
        return find(value).orElseThrow(() -> new IllegalArgumentException("未知日志 dataType: " + value));
    }

    public static Optional<LogDataType> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(type -> type.wire.equalsIgnoreCase(normalized))
                .findFirst();
    }
}
