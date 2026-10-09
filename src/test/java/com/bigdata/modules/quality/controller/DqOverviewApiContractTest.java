package com.bigdata.modules.quality.controller;

import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.quality.service.DqOverviewService;
import com.bigdata.modules.quality.vo.QualityOverviewVO;
import com.bigdata.modules.quality.vo.QualityProblemTableVO;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 质量大盘接口契约回归。
 *
 * <p>「质检任务 / 运行记录 / 告警订阅」三个页签及其服务端链路已删除，因此本测试原先
 * 对 {@code /quality/alert}、{@code /quality/alert/log} 的路由与字段断言全部移除；
 * {@code /quality/overview} 的路由与 camelCase 字段名仍要锁住。</p>
 *
 * <p>同时锁住两件「删除」：{@code passRateTrend} / {@code blockCount} 两个字段不再返回
 * （数据源随运行实例表一起消失），{@code projectCode} 入参不再生效（项目归属原先只能由
 * 质检任务推导）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质量大盘接口契约：路由可达 + camelCase 字段名")
class DqOverviewApiContractTest {

    /** snake_case 字段名探测（如 pass_rate_trend / block_count / top_problem_tables） */
    private static final Pattern SNAKE_KEY = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private final ObjectMapper mapper = ContractMockMvcSupport.productionObjectMapper();

    @Mock
    private DqOverviewService dqOverviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DqOverviewController controller = new DqOverviewController();
        ReflectionTestUtils.setField(controller, "dqOverviewService", dqOverviewService);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    private JsonNode dataOf(String url) throws Exception {
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode body = mapper.readTree(json);
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

    private QualityOverviewVO overviewVO() {
        QualityProblemTableVO problem = new QualityProblemTableVO();
        problem.setDatabaseName("test_db");
        problem.setTableName("dwd_event_f");
        problem.setFailCount(3);
        problem.setOwnerName("zhangsan");

        QualityOverviewVO vo = new QualityOverviewVO();
        vo.setCoveredTableCount(12);
        vo.setTargetTableCount(30);
        vo.setRuleCoverage(0.4);
        vo.setRuleCount(21);
        vo.setEnabledRuleCount(18);
        vo.setTopProblemTables(List.of(problem));
        return vo;
    }

    @Nested
    @DisplayName("路由可达性")
    class RouteCoverage {

        @Test
        @DisplayName("前端已接通的大盘调用命中，无 404 / 405")
        void overviewIsMapped() throws Exception {
            when(dqOverviewService.getOverview(any(), any())).thenReturn(overviewVO());

            mockMvc.perform(get("/quality/overview")).andExpect(status().isOk());
            mockMvc.perform(get("/quality/overview?startDate=2026-10-01&endDate=2026-10-07"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("已下线的告警订阅端点应 404（路由真的被摘掉了）")
        void removedAlertRoutesAreGone() throws Exception {
            mockMvc.perform(get("/quality/alert")).andExpect(status().isNotFound());
            mockMvc.perform(get("/quality/alert/log")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("未映射的子路径应 404")
        void unknownSubPathIsNotFound() throws Exception {
            mockMvc.perform(get("/quality/overview/unknown")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("字段命名与字段集合")
    class Fields {

        @Test
        @DisplayName("大盘字段为 camelCase，且 TOP 问题表字段齐全")
        void overviewFieldsAreCamelCase() throws Exception {
            when(dqOverviewService.getOverview(any(), any())).thenReturn(overviewVO());

            JsonNode data = dataOf("/quality/overview");
            assertNoSnakeCaseKey(data, "data");

            assertEquals(12, data.get("coveredTableCount").asInt());
            assertEquals(30, data.get("targetTableCount").asInt());
            assertEquals(0.4, data.get("ruleCoverage").asDouble(), 1e-9);
            assertEquals(18, data.get("enabledRuleCount").asInt());

            JsonNode first = data.get("topProblemTables").get(0);
            assertEquals("test_db", first.get("databaseName").asText());
            assertEquals("dwd_event_f", first.get("tableName").asText());
            assertEquals(3, first.get("failCount").asInt());
            assertEquals("zhangsan", first.get("ownerName").asText());
        }

        @Test
        @DisplayName("运行数据相关字段不再返回：passRateTrend / blockCount 已随运行记录下线")
        void removedFieldsAreAbsent() throws Exception {
            when(dqOverviewService.getOverview(any(), any())).thenReturn(overviewVO());

            JsonNode data = dataOf("/quality/overview");
            assertFalse(data.has("passRateTrend"), "通过率趋势的数据源（运行实例）已删除，字段不应再出现在契约里");
            assertFalse(data.has("blockCount"), "阻断次数依赖任务处置策略，任务链路已删除");
        }

        @Test
        @DisplayName("日期窗口按名下推到服务层（projectCode 已移除，不再下推）")
        void dateWindowIsPassedThrough() throws Exception {
            when(dqOverviewService.getOverview(any(), any())).thenReturn(overviewVO());

            mockMvc.perform(get("/quality/overview")
                            .param("startDate", "2026-10-01")
                            .param("endDate", "2026-10-07"))
                    .andExpect(status().isOk());

            verify(dqOverviewService).getOverview(eq(LocalDate.of(2026, 10, 1)),
                    eq(LocalDate.of(2026, 10, 7)));
        }
    }
}
