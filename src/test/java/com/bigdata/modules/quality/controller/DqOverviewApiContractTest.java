package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.quality.service.DqAlertService;
import com.bigdata.modules.quality.service.DqOverviewService;
import com.bigdata.modules.quality.vo.QualityAlertLogVO;
import com.bigdata.modules.quality.vo.QualityAlertSubscriptionVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 质量大盘与告警接口契约回归。
 *
 * <p>本次新增了 {@code GET /quality/alert/log}（站内告警箱）—— 告警记录写进
 * {@code dq_alert_log} 却没有任何查询入口时，等于"发了没有、为什么没发"无从知晓。
 * 新接口同样要锁路由与字段命名。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质量大盘与告警接口契约：路由可达 + camelCase 字段名")
class DqOverviewApiContractTest {

    /** snake_case 字段名探测（如 check_status / fail_reason / send_time / rule_name） */
    private static final Pattern SNAKE_KEY = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private final ObjectMapper mapper = ContractMockMvcSupport.productionObjectMapper();

    @Mock
    private DqOverviewService dqOverviewService;
    @Mock
    private DqAlertService dqAlertService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DqOverviewController controller = new DqOverviewController();
        ReflectionTestUtils.setField(controller, "dqOverviewService", dqOverviewService);
        ReflectionTestUtils.setField(controller, "dqAlertService", dqAlertService);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    private static List<MockHttpServletRequestBuilder> frontendCalls() {
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        calls.add(get("/quality/overview"));
        calls.add(get("/quality/alert?current=1&size=10"));
        calls.add(post("/quality/alert").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":9,\"notifyOn\":\"ERROR,FAILED\",\"channels\":\"INBOX\","
                        + "\"receivers\":\"zhangsan\",\"enabled\":true}"));
        calls.add(get("/quality/alert/log?current=1&size=20"));
        calls.add(get("/quality/alert/log?status=FAILED"));
        return calls;
    }

    private JsonNode call(MockHttpServletRequestBuilder builder) throws Exception {
        String json = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }

    private JsonNode dataOf(MockHttpServletRequestBuilder builder) throws Exception {
        JsonNode body = call(builder);
        assertEquals(200, body.get("code").asInt(), "接口应返回 Result.success，body=" + body);
        return body.get("data");
    }

    private void assertNoSnakeCaseKey(JsonNode node, String path) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                assertFalse(SNAKE_KEY.matcher(key).matches(),
                        "接口返回字段名出现 snake_case：" + path + "." + key);
                assertNoSnakeCaseKey(entry.getValue(), path + "." + key);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoSnakeCaseKey(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private QualityAlertLogVO logVO() {
        QualityAlertLogVO vo = new QualityAlertLogVO();
        vo.setId(1L);
        vo.setRunId(100L);
        vo.setTaskId(9L);
        vo.setTaskName("广告域质检");
        vo.setRuleId(21L);
        vo.setRuleName("R_AD_001 事件主键唯一");
        vo.setChannel("WEBHOOK");
        vo.setReceiver("zhangsan,lisi");
        vo.setStatus("FAILED");
        vo.setFailReason("Webhook 返回 HTTP 500");
        vo.setSendTime(LocalDateTime.of(2026, 10, 7, 8, 0));
        vo.setCheckStatus("ERROR");
        return vo;
    }

    @Nested
    @DisplayName("路由可达性")
    class RouteCoverage {

        @Test
        @DisplayName("前端已接通的 5 个调用全部命中，无 404 / 405")
        void allFrontendCallsAreMapped() throws Exception {
            Page<QualityAlertLogVO> logs = new Page<>(1, 20);
            logs.setRecords(List.of(logVO()));
            when(dqAlertService.pageLogs(anyInt(), anyInt(), any(), any(), any())).thenReturn(logs);
            when(dqAlertService.page(anyInt(), anyInt(), any()))
                    .thenReturn(new Page<QualityAlertSubscriptionVO>(1, 10));
            when(dqOverviewService.getOverview(any(), any(), any())).thenReturn(null);

            List<MockHttpServletRequestBuilder> calls = frontendCalls();
            assertEquals(5, calls.size(), "路由表条数必须与已接通的调用数一致；新增接口时请同步本表");

            for (MockHttpServletRequestBuilder builder : calls) {
                mockMvc.perform(builder).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("未映射的子路径应 404")
        void unknownSubPathIsNotFound() throws Exception {
            mockMvc.perform(get("/quality/alert/log/1/unknown")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("字段命名（camelCase）")
    class FieldNaming {

        @Test
        @DisplayName("告警记录含 failReason / checkStatus / sendTime，且不出现 snake_case")
        void alertLogFieldsAreCamelCase() throws Exception {
            Page<QualityAlertLogVO> logs = new Page<>(1, 20);
            logs.setRecords(List.of(logVO()));
            when(dqAlertService.pageLogs(anyInt(), anyInt(), any(), any(), any())).thenReturn(logs);

            JsonNode data = dataOf(get("/quality/alert/log?current=1&size=20"));
            assertNoSnakeCaseKey(data, "data");

            JsonNode first = data.get("records").get(0);
            assertEquals("Webhook 返回 HTTP 500", first.get("failReason").asText());
            assertEquals("ERROR", first.get("checkStatus").asText(), "触发状态要能读到，否则看不出为什么告警");
            assertEquals("广告域质检", first.get("taskName").asText());
            assertEquals(21L, first.get("ruleId").asLong());
        }

        @Test
        @DisplayName("筛选参数按名下推（status）")
        void statusFilterIsPassedThrough() throws Exception {
            when(dqAlertService.pageLogs(anyInt(), anyInt(), any(), any(), any()))
                    .thenReturn(new Page<>(1, 20));

            call(get("/quality/alert/log").param("status", "FAILED"));

            verify(dqAlertService).pageLogs(eq(1), eq(20), any(), any(), eq("FAILED"));
        }
    }
}
