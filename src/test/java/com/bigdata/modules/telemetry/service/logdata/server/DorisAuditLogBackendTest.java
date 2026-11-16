package com.bigdata.modules.telemetry.service.logdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.config.properties.DorisAuditConfig;

class DorisAuditLogBackendTest {

    @Test
    @DisplayName("log.task 或非 slowquery：能力判定直接拒绝，不发起 JDBC 调用")
    void rejectsOtherDataTypesAndStreams() {
        DorisAuditLogBackend backend = backend(new DorisAuditConfig());

        BackendCapability task = backend.evaluate(spec(LogDataType.TASK, "slowquery"));
        assertThat(task.isAvailable()).isFalse();
        assertThat(task.code()).isEqualTo(CapabilityCode.LOG_DATA_TYPE_MISMATCH);

        BackendCapability stdout = backend.evaluate(spec(LogDataType.SERVICE, "stdout"));
        assertThat(stdout.isAvailable()).isFalse();
        assertThat(stdout.code()).isEqualTo(CapabilityCode.STREAM_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("缺少 jdbc-url / zone 非法：NOT_CONFIGURED 且带配置键")
    void requiresJdbcUrlAndZone() {
        DorisAuditConfig noUrl = new DorisAuditConfig();
        noUrl.setJdbcUrl("");
        BackendCapability noUrlCapability = backend(noUrl).evaluate(spec(LogDataType.SERVICE, "slowquery"));
        assertThat(noUrlCapability.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(noUrlCapability.requirements())
                .anySatisfy(requirement -> assertThat(requirement.key())
                        .contains("doris-audit[].jdbc-url"));

        DorisAuditConfig badZone = new DorisAuditConfig();
        badZone.setZone("Mars/Olympus");
        BackendCapability badZoneCapability = backend(badZone)
                .evaluate(spec(LogDataType.SERVICE, "slowquery"));
        assertThat(badZoneCapability.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(badZoneCapability.reason()).contains("zone");
    }

    @Test
    @DisplayName("审计行映射：message 取 stmt，结构化字段进 fields，时间按配置时区解析")
    void mapsAuditRow() {
        DorisAuditLogBackend backend = backend(new DorisAuditConfig());
        Map<String, String> row = new LinkedHashMap<>();
        row.put("time", "2026-11-15 09:48:18.789");
        row.put("query_time", "9345");
        row.put("client_ip", "172.20.0.1");
        row.put("user", "admin");
        row.put("db", "__internal_schema");
        row.put("state", "ERR");
        row.put("error_code", "0");
        row.put("error_message", "");
        row.put("query_id", "abc-123");
        row.put("sql_hash", "72f9fe4e");
        row.put("stmt", "SELECT * FROM __internal_schema.column_statistics");

        LogEntry entry = backend.toEntry(row, spec(LogDataType.SERVICE, "slowquery"), ZoneId.of("UTC"));

        assertThat(entry.message()).isEqualTo("SELECT * FROM __internal_schema.column_statistics");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-11-15T09:48:18.789Z"));
        assertThat(entry.fields())
                .containsEntry("queryTimeMs", "9345")
                .containsEntry("user", "admin")
                .containsEntry("db", "__internal_schema")
                .containsEntry("state", "ERR")
                .containsEntry("clientIp", "172.20.0.1")
                .containsEntry("sqlHash", "72f9fe4e")
                .containsEntry("queryId", "abc-123");
        assertThat(entry.hostId()).isEqualTo("cluster");
    }

    @Test
    @DisplayName("stmt 为空时用 query_id/state/耗时合成摘要，不返回空串")
    void synthesizesMessageWhenStmtBlank() {
        DorisAuditLogBackend backend = backend(new DorisAuditConfig());
        Map<String, String> row = new LinkedHashMap<>();
        row.put("time", "2026-11-15 09:48:18");
        row.put("query_time", "7400");
        row.put("query_id", "q-1");
        row.put("state", "OK");
        row.put("stmt", "");

        LogEntry entry = backend.toEntry(row, spec(LogDataType.SERVICE, "slowquery"), ZoneId.of("UTC"));

        assertThat(entry.message()).contains("query_id=q-1", "state=OK", "7400ms");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-11-15T09:48:18Z"));
    }

    @Test
    @DisplayName("SQL 模板固定：只有 3 个时间/阈值占位符，表名与排序列不可由请求改写")
    void sqlTemplateIsFixed() {
        assertThat(DorisAuditLogBackend.SQL)
                .startsWith("SELECT `time`, query_time, client_ip, `user`, `db`, state, "
                        + "error_code, error_message, query_id, sql_hash, stmt")
                .contains("FROM __internal_schema.audit_log")
                .contains("WHERE `time` >= ? AND `time` <= ? AND query_time >= ?")
                .contains("ORDER BY `time` DESC LIMIT ")
                .doesNotContain("${", "%s");
    }

    @Test
    @DisplayName("审计表不存在：BACKEND_UNAVAILABLE + 配置补齐项，允许目录回退 local-disk")
    void missingAuditTableIsAvailabilityFailure() {
        BackendException error = backend(new DorisAuditConfig())
                .map(new SQLException("Unknown table 'audit_log'", "42S02", 1146));

        assertThat(error.getCode()).isEqualTo(CapabilityCode.BACKEND_UNAVAILABLE);
        assertThat(error.availabilityFailure()).isTrue();
        assertThat(error.getMessage()).contains("审计表不可用");
        assertThat(error.getRequirements()).anySatisfy(requirement ->
                assertThat(requirement.description()).contains("local-disk"));
    }

    @Test
    @DisplayName("认证失败映射 UPSTREAM_UNAUTHORIZED，不误判为可回退的可用性故障")
    void accessDeniedIsNotAvailabilityFailure() {
        BackendException error = backend(new DorisAuditConfig())
                .map(new SQLException("Access denied for user 'root'", "28000", 1045));

        assertThat(error.getCode()).isEqualTo(CapabilityCode.UPSTREAM_UNAUTHORIZED);
        assertThat(error.availabilityFailure()).isFalse();
    }

    private static DorisAuditLogBackend backend(DorisAuditConfig config) {
        return new DorisAuditLogBackend("doris-audit", config);
    }

    private static LogQuerySpec spec(LogDataType dataType, String stream) {
        return new LogQuerySpec(new Target("doris", "doris.fe", "cluster"), dataType, stream,
                null, new LogFilter(List.of(), List.of(), LogFilter.MatchMode.ALL, false, false),
                null, 100, 1024 * 1024, Map.of(), null);
    }
}
