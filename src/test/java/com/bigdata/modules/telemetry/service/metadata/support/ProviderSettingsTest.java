package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.service.metadata.support.JdbcMetadataClient.ReadOnlyStatement;

class ProviderSettingsTest {

    private final ProviderSettings settings = new ProviderSettings(properties());

    @Test
    @DisplayName("缺省不启用；缺必填项抛 NOT_CONFIGURED 并带配置键")
    void requiredKeyIsTyped() {
        assertThat(settings.enabled("clickhouse-http")).isFalse();
        assertThat(settings.enabled("missing")).isFalse();

        assertThatThrownBy(() -> settings.required("clickhouse-http", "jdbc-url"))
                .isInstanceOf(BackendException.class)
                .satisfies(error -> {
                    BackendException failure = (BackendException) error;
                    assertThat(failure.getCode()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
                    assertThat(failure.getRequirements()).extracting(Requirement::kind)
                            .containsExactly(RequirementKind.CONFIG);
                    assertThat(failure.getRequirements()).extracting(Requirement::key)
                            .containsExactly(
                                    "telemetry.metadata.providers.clickhouse-http.jdbc-url");
                });
    }

    @Test
    @DisplayName("读取基本类型 / 列表 / 嵌套映射")
    void readsValues() {
        assertThat(settings.string("clickhouse-http", "base-url"))
                .isEqualTo("http://127.0.0.1:8123");
        assertThat(settings.intValue("clickhouse-http", "max-items", 10)).isEqualTo(500);
        assertThat(settings.longValue("clickhouse-http", "timeout-ms", 1000)).isEqualTo(3000);
        assertThat(settings.list("clickhouse-http", "sections"))
                .containsExactly("server_settings", "settings");
        assertThat(settings.map("clickhouse-http", "auth"))
                .containsEntry("type", "basic").containsEntry("username", "default");
        assertThat(settings.list("clickhouse-http", "missing")).isEmpty();
    }

    @Test
    @DisplayName("JDBC 白名单只有只读语句，不含 DDL / DML")
    void jdbcWhitelistIsReadOnly() {
        assertThat(JdbcMetadataClient.ReadOnlyStatement.values())
                .extracting(JdbcMetadataClient.ReadOnlyStatement::sql)
                .allSatisfy(sql -> assertThat(sql.toUpperCase())
                        .doesNotContain("INSERT").doesNotContain("UPDATE")
                        .doesNotContain("DELETE").doesNotContain("DROP")
                        .doesNotContain("ALTER").doesNotContain("CREATE"))
                .contains("SHOW FRONTENDS", "ADMIN SHOW FRONTEND CONFIG");
    }

    private TelemetryMetadataProperties properties() {
        Map<String, Object> clickhouse = new LinkedHashMap<>();
        clickhouse.put("base-url", "http://127.0.0.1:8123");
        clickhouse.put("max-items", 500);
        clickhouse.put("timeout-ms", 3000);
        clickhouse.put("sections", List.of("server_settings", "settings"));
        clickhouse.put("auth", Map.of("type", "basic", "username", "default"));

        Map<String, Map<String, Object>> providers = new LinkedHashMap<>();
        providers.put("clickhouse-http", clickhouse);
        TelemetryMetadataProperties properties = new TelemetryMetadataProperties();
        properties.setProviders(providers);
        return properties;
    }
}
