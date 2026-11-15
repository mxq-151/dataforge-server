package com.bigdata.common.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计日志入参脱敏：DTO、嵌套对象、集合里的敏感字段都不能以原文落库。
 *
 * <p>修复前只判断「参数本身是不是 String 且文本里含 password/secret/token」，
 * 传 DTO 时整个对象被原样序列化，密码/密钥/证件号全部明文写入 request_params。</p>
 */
@DisplayName("Common模块 - 审计日志入参脱敏")
class AuditLogAspectMaskingTest {

    private AuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuditLogAspect();
        ReflectionTestUtils.setField(aspect, "objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("DTO 中的密码、token、密钥、证件号必须脱敏")
    void masksSensitiveFieldsInDto() {
        UserPayload payload = new UserPayload();

        String json = capture(payload);

        assertNotNull(json);
        assertFalse(json.contains("P@ssw0rd"), "密码原文不得落库: " + json);
        assertFalse(json.contains("tok-123"), "token 原文不得落库: " + json);
        assertFalse(json.contains("sk-456"), "密钥原文不得落库: " + json);
        assertFalse(json.contains("110101199001011234"), "证件号原文不得落库: " + json);
        assertTrue(json.contains("alice"), "非敏感字段应保留: " + json);
        assertTrue(json.contains("******"), json);
    }

    @Test
    @DisplayName("嵌套对象与集合中的敏感字段同样脱敏")
    void masksNestedObjectsAndCollections() {
        UserPayload outer = new UserPayload();
        outer.friends = List.of(new UserPayload(), new UserPayload());

        String json = capture(outer);

        assertNotNull(json);
        assertFalse(json.contains("P@ssw0rd"), json);
        assertFalse(json.contains("tok-123"), json);
        assertFalse(json.contains("sk-456"), json);
    }

    @Test
    @DisplayName("字符串参数里的 password=... 仍按原语义整体屏蔽")
    void masksInlineCredentialText() {
        String json = capture(new Object[]{"password=abc123"});

        assertNotNull(json);
        assertFalse(json.contains("abc123"), json);
    }

    private String capture(Object argument) {
        return capture(new Object[]{argument});
    }

    private String capture(Object[] args) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(sampleMethod());
        return ReflectionTestUtils.invokeMethod(aspect, "captureRequestParams", joinPoint, null);
    }

    private Method sampleMethod() {
        try {
            return PayloadHandler.class.getMethod("handle", UserPayload.class, List.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static class PayloadHandler {
        public void handle(UserPayload payload, List<UserPayload> list) {
            // 仅用于提供参数名
        }
    }

    @SuppressWarnings("unused")
    public static class UserPayload {
        public String username = "alice";
        public String password = "P@ssw0rd";
        public String idCard = "110101199001011234";
        public Credential credential = new Credential();
        public List<UserPayload> friends = List.of();
    }

    @SuppressWarnings("unused")
    public static class Credential {
        public String accessKey = "AK-123";
        public String secretKey = "sk-456";
        public String token = "tok-123";
    }
}
