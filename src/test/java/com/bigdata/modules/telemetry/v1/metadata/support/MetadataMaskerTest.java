package com.bigdata.modules.telemetry.v1.metadata.support;

import com.bigdata.modules.telemetry.v1.metadata.model.MaskingMode;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.support.SensitiveDataMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataMaskerTest {

    private final MetadataMasker masker = new MetadataMasker(new SensitiveDataMasker());

    @Test
    @DisplayName("默认敏感键整值脱敏为 ***")
    void masksDefaultSensitiveKeys() {
        assertThat(masker.mask("fe_config", "auth_token", "abc", false, List.of()).value())
                .isEqualTo("***");
        assertThat(masker.mask("ssl", "ssl.private_key", "abc", false, List.of()).sensitive())
                .isTrue();
        assertThat(masker.mask("sasl", "sasl.jaas.config", "x", false, List.of()).redacted())
                .isTrue();
    }

    @Test
    @DisplayName("词边界：不误伤 key_buffer_size / max_keys，裸 key 仍脱敏")
    void doesNotOverMaskOrdinaryKeyNames() {
        MetadataMasker.MaskedValue buffer = masker.mask("server_settings", "key_buffer_size",
                "8388608", false, List.of());
        assertThat(buffer.sensitive()).isFalse();
        assertThat(buffer.value()).isEqualTo("8388608");

        assertThat(masker.mask("server_settings", "max_keys", "1000", false, List.of())
                .sensitive()).isFalse();
        assertThat(masker.mask("fe_config", "enable_token_check", "true", false, List.of())
                .sensitive()).isFalse();
        assertThat(masker.mask("fe_config", "key_store_path", "/opt/doris.keystore", false,
                List.of()).sensitive()).isFalse();
        assertThat(masker.mask("server_settings", "key", "plain", false, List.of()).redacted())
                .isTrue();
        assertThat(masker.mask("server_settings", "access_key", "plain", false, List.of())
                .redacted()).isTrue();
        assertThat(masker.mask("fe_config", "auth_token", "plain", false, List.of()).redacted())
                .isTrue();
    }

    @Test
    @DisplayName("任一值里的内嵌凭据都要清洗")
    void masksInlineCredential() {
        MetadataMasker.MaskedValue masked = masker.mask("hive-site", "javax.jdo.option.ConnectionURL",
                "jdbc:mysql://h/db?user=hive&password=hive-pass", false, List.of());

        assertThat(masked.value()).doesNotContain("hive-pass");
        assertThat(masked.sensitive()).isTrue();
        assertThat(masked.redacted()).isTrue();
    }

    @Test
    @DisplayName("provider 规则按 section + key 命中，且不能关掉默认规则")
    void appliesProviderRules() {
        List<MaskingRule> rules = List.of(new MaskingRule("^fe_config$", "^enable_profile$",
                MaskingMode.WHOLE_VALUE, "***"));

        assertThat(masker.mask("fe_config", "enable_profile", "secret", false, rules).redacted())
                .isTrue();
        assertThat(masker.mask("be_config", "enable_profile", "secret", false, rules).redacted())
                .isFalse();
        assertThat(masker.mask("fe_config", "password", "secret", false, rules).redacted())
                .isTrue();
    }

    @Test
    @DisplayName("空值仍标记 sensitive，但没有明文可脱")
    void emptyValueKeepsSensitiveFlag() {
        MetadataMasker.MaskedValue masked = masker.mask("fe_config", "auth_token", "",
                false, List.of());

        assertThat(masked.sensitive()).isTrue();
        assertThat(masked.redacted()).isFalse();
        assertThat(masked.value()).isEmpty();
    }

    @Test
    @DisplayName("非法正则启动期即失败")
    void rejectsInvalidRule() {
        assertThatThrownBy(() -> MetadataMasker.validate(
                new MaskingRule("([", "^.*$", MaskingMode.WHOLE_VALUE, "***")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
