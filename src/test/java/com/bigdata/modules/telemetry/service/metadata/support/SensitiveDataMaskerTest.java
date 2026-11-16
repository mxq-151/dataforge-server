package com.bigdata.modules.telemetry.service.metadata.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    @DisplayName("非敏感键里的内嵌凭据也要清洗")
    void masksInlineCredential() {
        String value = "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"admin\" password=\"secret\" user_alice=\"alice-secret\";";

        String masked = masker.maskText(value);

        assertThat(masked).doesNotContain("secret").doesNotContain("alice-secret");
        assertThat(masked).contains("password=***");
    }
}
