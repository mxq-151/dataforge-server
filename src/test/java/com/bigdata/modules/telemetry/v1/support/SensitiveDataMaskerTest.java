package com.bigdata.modules.telemetry.v1.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    @DisplayName("敏感键整体替换，非敏感键原样保留")
    void masksSensitiveKeys() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("security.protocol", "SASL_PLAINTEXT");
        values.put("sasl.jaas.config", "x");
        values.put("password", "p@ss");
        values.put("api-token", "abc");
        values.put("authorization", "Bearer abc");
        values.put("parallelism.default", "4");

        Map<String, String> masked = masker.mask(values);

        assertThat(masked).containsEntry("password", "***")
                .containsEntry("api-token", "***")
                .containsEntry("authorization", "***");
        assertThat(masked.get("sasl.jaas.config")).isEqualTo("***");
        assertThat(masked).containsEntry("security.protocol", "SASL_PLAINTEXT")
                .containsEntry("parallelism.default", "4");
    }

    @Test
    @DisplayName("非敏感键里的内嵌凭据也要清洗")
    void masksInlineCredential() {
        String value = "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"admin\" password=\"secret\" user_alice=\"alice-secret\";";

        String masked = masker.maskText(value);

        assertThat(masked).doesNotContain("secret").doesNotContain("alice-secret");
        assertThat(masked).contains("password=***");
    }

    @Test
    @DisplayName("嵌套 sections 递归脱敏")
    void masksNestedSections() {
        Map<String, Object> sections = Map.of(
                "job", Map.of("parallelism.default", "2", "db.password", "p"),
                "list", List.of(Map.of("token", "t"), "plain"));

        Map<String, Object> masked = masker.maskSections(sections);

        assertThat(masked.get("job")).isEqualTo(Map.of("parallelism.default", "2", "db.password", "***"));
        assertThat(masked.get("list")).isEqualTo(List.of(Map.of("token", "***"), "plain"));
    }
}
