package com.bigdata.modules.telemetry.v1.metadata.support;

import com.bigdata.modules.telemetry.v1.metadata.model.ConfigEntryView;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingMode;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.support.RegexGuard;
import com.bigdata.modules.telemetry.v1.support.SensitiveDataMasker;
import com.google.re2j.Pattern;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * metadata 的核心脱敏：三层叠加，provider 只能「加重」，不能关掉默认规则。
 *
 * <ol>
 *   <li>默认键规则：password / secret / token / credential / keytab / authorization /
 *       {@code sasl.jaas.config} 等，以及 {@code access_key} / {@code private_key} 这类组合键；
 *       裸 {@code key} 只在键名整体等于 {@code key} 时命中，避免误伤
 *       {@code key_buffer_size}、{@code max_keys}。</li>
 *   <li>内嵌凭据清洗：所有值都处理 {@code password="..."}、{@code user_alice="..."} 等赋值。</li>
 *   <li>provider 声明的 {@link MaskingRule}。</li>
 * </ol>
 *
 * <p>命中即 {@code value="***"}、{@code sensitive=true}、{@code redacted=true}；
 * 原值为空时仍标记 sensitive，但 redacted=false（没有明文可脱）。</p>
 */
@Component
public class MetadataMasker {

    /**
     * 默认敏感键：
     * <ul>
     *   <li>password / secret / credential / keytab / authorization 作为任意段出现；</li>
     *   <li>token / key 只在<b>最后一段</b>出现时命中（{@code auth_token}、{@code ssl.client.key}），
     *       避免误伤 {@code enable_token_check}、{@code key_store_path}；</li>
     *   <li>access_key / private_key / secret_key / api_key 等组合键；</li>
     *   <li>sasl.jaas / jaas.config 作为任意段出现。</li>
     * </ul>
     */
    private static final Pattern DEFAULT_SENSITIVE_KEY = Pattern.compile(
            "(?i)((^|[._-])(password|passwd|pwd|secret|credential|keytab|authorization)([._-]|$))"
                    + "|((^|[._-])(token|key)$)"
                    + "|((^|[._-])(access|private|secret|api|auth|public)[._-]?key([._-]|$))"
                    + "|((^|[._-])sasl[._-]?jaas([._-]|$))"
                    + "|((^|[._-])jaas[._-]?config([._-]|$))");

    private final SensitiveDataMasker defaults;

    public MetadataMasker(SensitiveDataMasker defaults) {
        this.defaults = defaults;
    }

    /** 启动期校验 provider 规则，非法正则直接启动失败。 */
    public static void validate(MaskingRule rule) {
        if (rule == null) {
            return;
        }
        if (rule.sectionPattern() != null) {
            RegexGuard.validate(rule.sectionPattern());
        }
        if (rule.keyPattern() != null) {
            RegexGuard.validate(rule.keyPattern());
        }
    }

    public MaskedValue mask(String section, String key, String value, boolean sensitiveHint,
                            List<MaskingRule> rules) {
        boolean sensitive = sensitiveHint || isDefaultSensitiveKey(key);
        String replacement = ConfigEntryView.REDACTED;

        if (rules != null) {
            for (MaskingRule rule : rules) {
                if (!matches(rule, section, key)) {
                    continue;
                }
                if (rule.mode() == MaskingMode.INLINE_CREDENTIAL && value != null
                        && !value.isEmpty()) {
                    String masked = defaults.maskText(value);
                    if (!masked.equals(value)) {
                        return new MaskedValue(masked, true, true);
                    }
                    continue;
                }
                sensitive = true;
                replacement = rule.replacement();
            }
        }

        if (sensitive) {
            if (value == null || value.isEmpty()) {
                return new MaskedValue(value, true, false);
            }
            return new MaskedValue(replacement, true, true);
        }

        if (value == null || value.isEmpty()) {
            return new MaskedValue(value, false, false);
        }
        String masked = defaults.maskText(value);
        if (!masked.equals(value)) {
            return new MaskedValue(masked, true, true);
        }
        return new MaskedValue(value, false, false);
    }

    private boolean isDefaultSensitiveKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return DEFAULT_SENSITIVE_KEY.matcher(key).find();
    }

    private boolean matches(MaskingRule rule, String section, String key) {
        if (rule.sectionPattern() != null
                && !Pattern.compile(rule.sectionPattern(), Pattern.CASE_INSENSITIVE)
                .matcher(section == null ? "" : section).matches()) {
            return false;
        }
        return rule.keyPattern() == null
                || Pattern.compile(rule.keyPattern(), Pattern.CASE_INSENSITIVE)
                .matcher(key == null ? "" : key).matches();
    }

    /** 单条配置的脱敏结果。 */
    public record MaskedValue(String value, boolean sensitive, boolean redacted) {
    }
}
