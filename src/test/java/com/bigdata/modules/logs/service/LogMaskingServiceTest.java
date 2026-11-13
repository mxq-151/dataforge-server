package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("日志出口脱敏")
class LogMaskingServiceTest {

    @Test
    @DisplayName("密码 / token 键值对脱敏")
    void maskKeyValue() {
        LogMaskingService service = new LogMaskingService(LogTestSupport.properties());
        String masked = service.mask("password=abc123 token: xyz456");
        assertFalse(masked.contains("abc123"));
        assertFalse(masked.contains("xyz456"));
        assertTrue(masked.contains("password=******"));
    }

    @Test
    @DisplayName("Bearer token 脱敏")
    void maskBearer() {
        LogMaskingService service = new LogMaskingService(LogTestSupport.properties());
        String masked = service.mask("Authorization: Bearer abcdefgh12345678");
        assertFalse(masked.contains("abcdefgh12345678"));
        assertTrue(masked.contains("Bearer ******"));
    }

    @Test
    @DisplayName("JDBC URL 中的密码与 URL query 敏感参数脱敏")
    void maskJdbcAndUrl() {
        LogMaskingService service = new LogMaskingService(LogTestSupport.properties());
        assertTrue(service.mask("jdbc:mysql://root:secret@db:3306/x").contains("******"));
        String url = "http://host/path?token=abcdef&x=1";
        assertFalse(service.mask(url).contains("abcdef"));
    }

    @Test
    @DisplayName("支持追加自定义脱敏规则")
    void maskExtraPattern() {
        LogAccessProperties properties = LogTestSupport.properties();
        properties.getMasking().getExtraPatterns().add("sk-[A-Za-z0-9]{6,}=>sk-******");
        LogMaskingService service = new LogMaskingService(properties);

        String masked = service.mask("key=sk-abcdef123456 end");
        assertFalse(masked.contains("abcdef123456"));
        assertTrue(masked.contains("sk-******"));
    }

    @Test
    @DisplayName("关闭脱敏后原样返回")
    void disabledMasking() {
        LogAccessProperties properties = LogTestSupport.properties();
        properties.getMasking().setEnabled(false);
        LogMaskingService service = new LogMaskingService(properties);

        assertEquals("password=abc123", service.mask("password=abc123"));
    }
}
