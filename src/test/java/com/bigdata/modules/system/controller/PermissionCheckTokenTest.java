package com.bigdata.modules.system.controller;

import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.system.dto.DataResourcePermissionCheckDTO;
import com.bigdata.modules.system.service.DataResourcePermissionCheckService;
import com.bigdata.modules.system.vo.DataResourcePermissionCheckVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /system/permission/check} 的共享密钥校验回归。
 * <p>
 * 该接口是匿名放行（无 JWT）的，外部调用能否被拦住全靠请求头
 * {@code X-Permission-Check-Token}，因此必须有用例守住「验不了就拒绝」的约定：
 * 未配置密钥、请求头缺失、密钥不匹配，一律不得进入权限判定逻辑。
 * </p>
 */
class PermissionCheckTokenTest {

    private static final String SECRET = "unit-test-secret";
    private static final String OTHER_SECRET = "wrong-secret";
    private static final String HEADER = "X-Permission-Check-Token";
    private static final String BODY = "{\"username\":\"hadoop\",\"resources\":[]}";

    private StubCheckService checkService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        checkService = new StubCheckService();
        PermissionController controller = new PermissionController();
        setField(controller, "checkSecret", SECRET);
        setField(controller, "dataResourcePermissionCheckService", checkService);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    @Test
    void rejectsWhenHeaderMissing() throws Exception {
        mockMvc.perform(post("/system/permission/check")
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        assertFalse(checkService.invoked, "密钥缺失时不应进入权限判定");
    }

    @Test
    void rejectsWhenSecretMismatch() throws Exception {
        mockMvc.perform(post("/system/permission/check")
                        .header(HEADER, OTHER_SECRET)
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        assertFalse(checkService.invoked, "密钥不匹配时不应进入权限判定");
    }

    @Test
    void rejectsWhenSecretNotConfigured() throws Exception {
        PermissionController controller = new PermissionController();
        setField(controller, "checkSecret", "");
        setField(controller, "dataResourcePermissionCheckService", checkService);
        MockMvc unconfigured = ContractMockMvcSupport.productionMockMvc(controller);

        unconfigured.perform(post("/system/permission/check")
                        .header(HEADER, SECRET)
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503));

        assertFalse(checkService.invoked, "服务端未配置密钥时不应进入权限判定");
    }

    @Test
    void allowsWhenSecretMatches() throws Exception {
        mockMvc.perform(post("/system/permission/check")
                        .header(HEADER, SECRET)
                        .contentType(APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowed").value(true));

        assertTrue(checkService.invoked, "密钥正确时应正常进入权限判定");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = PermissionController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 记录是否被调用的服务桩：{@code check} 一旦被调用即说明鉴权已放行。 */
    private static class StubCheckService extends DataResourcePermissionCheckService {

        private boolean invoked;

        @Override
        public DataResourcePermissionCheckVO check(DataResourcePermissionCheckDTO request) {
            invoked = true;
            DataResourcePermissionCheckVO vo = new DataResourcePermissionCheckVO();
            vo.setAllowed(true);
            return vo;
        }
    }
}