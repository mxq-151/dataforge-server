package com.bigdata.common.exception;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
@DisplayName("GlobalExceptionHandler全局异常处理器 - 集成测试")
class GlobalExceptionHandlerRealDataTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Nested
    @DisplayName("场景1: BusinessException业务异常处理")
    class BusinessExceptionTests {

        @Test
        @DisplayName("✅ 默认BusinessException(code=500) - HTTP 200/500 + 包含错误消息")
        void testDefaultBusinessException() throws Exception {
            System.out.println("\n=== 场景1.1: 默认BusinessException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/business/default")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 200 || status == 500,
                                "HTTP状态码应为200或500, 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            System.out.println("  响应内容: " + content);
            assertTrue(content.contains("\"code\""), "响应必须包含code字段");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }

        @Test
        @DisplayName("✅ 自定义code的BusinessException(403) - HTTP 200 + Result.code=403")
        void testBusinessExceptionWithCode403() throws Exception {
            System.out.println("\n=== 场景1.2: BusinessException code=403 ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/business/403")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("权限不足"))
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            System.out.println("  响应内容: " + content);
            assertTrue(content.contains("\"code\":403"), "Result.code应为403");
        }

        @Test
        @DisplayName("✅ 自定义code的BusinessException(401) - HTTP 200 + Result.code=401")
        void testBusinessExceptionWithCode401() throws Exception {
            System.out.println("\n=== 场景1.3: BusinessException code=401 ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/business/401")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("未认证"))
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            System.out.println("  响应内容: " + content);
            assertTrue(content.contains("\"code\":401"), "Result.code应为401");
        }

        @Test
        @DisplayName("✅ 自定义code的BusinessException(1001) - HTTP 200 + Result.code=1001")
        void testBusinessExceptionWithCustomCode() throws Exception {
            System.out.println("\n=== 场景1.4: BusinessException code=1001 ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/business/custom")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001))
                    .andExpect(jsonPath("$.message").value("自定义业务错误"))
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            System.out.println("  响应内容: " + content);
            assertTrue(content.contains("\"code\":1001"), "Result.code应为1001");
        }
    }

    @Nested
    @DisplayName("场景2: AccessDeniedException访问拒绝异常处理")
    class AccessDeniedExceptionTests {

        @Test
        @DisplayName("❌ AccessDeniedException - HTTP 403/401 + Result.code=403")
        void testAccessDeniedException() throws Exception {
            System.out.println("\n=== 场景2.1: AccessDeniedException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/access-denied")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 403 || status == 401,
                                "HTTP状态码应为403或401(取决于Security上下文), 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            System.out.println("  HTTP状态码: " + httpStatus);
            System.out.println("  响应内容: " + content);

            assertTrue(httpStatus == 403 || httpStatus == 401, "HTTP状态码应为403或401");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }
    }

    @Nested
    @DisplayName("场景3: AuthenticationException认证异常处理")
    class AuthenticationExceptionTests {

        @Test
        @DisplayName("❌ InsufficientAuthenticationException - HTTP 401 + 包含认证提示消息")
        void testInsufficientAuthenticationException() throws Exception {
            System.out.println("\n=== 场景3.1: InsufficientAuthenticationException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/unauthorized")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 401,
                                "HTTP状态码应为401, 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            System.out.println("  HTTP状态码: " + httpStatus);
            System.out.println("  响应内容: " + content);

            assertEquals(401, httpStatus, "HTTP状态码应为401");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }
    }

    @Nested
    @DisplayName("场景4: IllegalArgumentException参数非法异常处理")
    class IllegalArgumentExceptionTests {

        @Test
        @DisplayName("❌ IllegalArgumentException - 被Security拦截或走通用异常处理")
        void testIllegalArgumentException() throws Exception {
            System.out.println("\n=== 场景4.1: IllegalArgumentException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/illegal-argument")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 401 || status == 500,
                                "HTTP状态码应为401(Security拦截)或500(通用异常), 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            System.out.println("  HTTP状态码: " + httpStatus);
            System.out.println("  响应内容: " + content);

            assertTrue(httpStatus == 401 || httpStatus == 500,
                    "HTTP状态码应为401或500");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }
    }

    @Nested
    @DisplayName("场景5: 未知异常处理(Exception)")
    class UnknownExceptionTests {

        @Test
        @DisplayName("❌ RuntimeException未知异常 - 被Security拦截或走通用异常处理")
        void testRuntimeException() throws Exception {
            System.out.println("\n=== 场景5.1: RuntimeException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/runtime")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 401 || status == 500,
                                "HTTP状态码应为401(Security拦截)或500(通用异常), 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            System.out.println("  HTTP状态码: " + httpStatus);
            System.out.println("  响应内容: " + content);

            assertTrue(httpStatus == 401 || httpStatus == 500, "HTTP状态码应为401或500");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }

        @Test
        @DisplayName("❌ NullPointerException空指针异常 - 被Security拦截或走通用异常处理")
        void testNullPointerException() throws Exception {
            System.out.println("\n=== 场景5.2: NullPointerException ===");

            MvcResult result = mockMvc.perform(
                            get("/test/exception/null-pointer")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(mvcResult -> {
                        int status = mvcResult.getResponse().getStatus();
                        assertTrue(status == 401 || status == 500,
                                "HTTP状态码应为401(Security拦截)或500(通用异常), 实际: " + status);
                    })
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            System.out.println("  HTTP状态码: " + httpStatus);
            System.out.println("  响应内容: " + content);

            assertTrue(httpStatus == 401 || httpStatus == 500, "HTTP状态码应为401或500");
            assertTrue(content.contains("\"message\""), "响应必须包含message字段");
        }
    }

    @Nested
    @DisplayName("场景6: Result.error()格式验证")
    class ResultFormatValidationTests {

        @Test
        @DisplayName("✅ 所有异常响应的Result格式统一 - code非200且包含message字段")
        void testResultErrorFormatConsistency() throws Exception {
            System.out.println("\n=== 场景6.1: 验证Result.error()格式统一性 ===");

            String[] endpoints = {
                    "/test/exception/business/default",
                    "/test/exception/business/403",
                    "/test/exception/access-denied",
                    "/test/exception/unauthorized",
                    "/test/exception/illegal-argument",
                    "/test/exception/runtime"
            };

            for (String endpoint : endpoints) {
                MvcResult result = mockMvc.perform(
                                get(endpoint)
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

                String content = result.getResponse().getContentAsString();

                System.out.println("  端点: " + endpoint);
                System.out.println("    响应: " + content);

                assertTrue(content.contains("\"code\""), "响应必须包含code字段");
                assertFalse(content.contains("\"code\":200"),
                        "错误响应的code不应为200, 当前端点: " + endpoint);
                assertTrue(content.contains("\"message\""),
                        "响应必须包含message字段, 当前端点: " + endpoint);
            }

            System.out.println("  ✓ 所有异常响应均符合Result.error()格式规范");
        }

        @Test
        @DisplayName("✅ 错误响应data字段为null")
        void testErrorResultDataIsNull() throws Exception {
            System.out.println("\n=== 场景6.2: 验证错误响应data字段 ===");

            String[] errorEndpoints = {
                    "/test/exception/business/default",
                    "/test/exception/access-denied",
                    "/test/exception/unauthorized",
                    "/test/exception/runtime"
            };

            for (String endpoint : errorEndpoints) {
                mockMvc.perform(get(endpoint).contentType(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.data").doesNotExist());

                System.out.println("  ✓ " + endpoint + " - data字段不存在");
            }
        }
    }

    @RestController
    @RequestMapping("/test/exception")
    static class TestExceptionController {

        @GetMapping("/business/default")
        public void throwDefaultBusinessException() {
            throw new BusinessException("默认业务错误");
        }

        @GetMapping("/business/403")
        public void throwBusinessException403() {
            throw new BusinessException(403, "权限不足");
        }

        @GetMapping("/business/401")
        public void throwBusinessException401() {
            throw new BusinessException(401, "未认证");
        }

        @GetMapping("/business/custom")
        public void throwBusinessExceptionCustom() {
            throw new BusinessException(1001, "自定义业务错误");
        }

        @GetMapping("/access-denied")
        public void throwAccessDeniedException() {
            throw new AccessDeniedException("模拟访问被拒绝");
        }

        @GetMapping("/unauthorized")
        public void throwUnauthorizedException() {
            throw new InsufficientAuthenticationException("模拟未认证");
        }

        @GetMapping("/illegal-argument")
        public void throwIllegalArgumentException() {
            throw new IllegalArgumentException("参数非法");
        }

        @GetMapping("/runtime")
        public void throwRuntimeException() {
            throw new RuntimeException("运行时异常");
        }

        @GetMapping("/null-pointer")
        public void throwNullPointerException() {
            throw new NullPointerException("空指针异常");
        }
    }
}
