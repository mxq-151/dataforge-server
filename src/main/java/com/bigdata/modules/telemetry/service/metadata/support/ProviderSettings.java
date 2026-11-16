package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * provider 配置读取：统一从 {@code telemetry.metadata.providers.<id>} 取值。
 *
 * <p>新增 provider 只加配置块，不改核心配置类。缺必填项时抛 {@code NOT_CONFIGURED} 并带上
 * 具体配置键，便于调用方直接补齐。</p>
 */
@Component
public class ProviderSettings {

    private final TelemetryMetadataProperties properties;

    public ProviderSettings(TelemetryMetadataProperties properties) {
        this.properties = properties;
    }

    public boolean present(String providerId) {
        return block(providerId) != null;
    }

    public boolean enabled(String providerId) {
        return bool(providerId, "enabled", false);
    }

    public String string(String providerId, String key) {
        Object value = block(providerId) == null ? null : block(providerId).get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public String required(String providerId, String key) {
        String value = string(providerId, key);
        if (value == null) {
            throw notConfigured(providerId, key, "缺少必填配置");
        }
        return value;
    }

    public int intValue(String providerId, String key, int defaultValue) {
        String value = string(providerId, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw notConfigured(providerId, key, "必须是整数");
        }
    }

    public long longValue(String providerId, String key, long defaultValue) {
        String value = string(providerId, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw notConfigured(providerId, key, "必须是整数");
        }
    }

    public boolean bool(String providerId, String key, boolean defaultValue) {
        String value = string(providerId, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String providerId, String key) {
        Object value = block(providerId) == null ? null : block(providerId).get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> items) {
            List<String> result = new ArrayList<>();
            for (Object item : items) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        throw notConfigured(providerId, key, "必须是列表");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String providerId, String key) {
        Object value = block(providerId) == null ? null : block(providerId).get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        throw notConfigured(providerId, key, "必须是映射");
    }

    /** 对象列表（如 {@code manual.entries}）。 */
    public List<Map<String, Object>> objects(String providerId, String key) {
        Object value = block(providerId) == null ? null : block(providerId).get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> items) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    raw.forEach((k, v) -> entry.put(String.valueOf(k), v));
                    result.add(entry);
                }
            }
            return result;
        }
        throw notConfigured(providerId, key, "必须是对象列表");
    }

    private Map<String, Object> block(String providerId) {
        return properties.getProviders().get(providerId);
    }

    private BackendException notConfigured(String providerId, String key, String detail) {
        String configKey = "telemetry.metadata.providers." + providerId + "." + key;
        return new BackendException(CapabilityCode.NOT_CONFIGURED,
                "provider " + providerId + " " + detail + ": " + configKey,
                List.of(new Requirement(RequirementKind.CONFIG, configKey, detail)));
    }
}
