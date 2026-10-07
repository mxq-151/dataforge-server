package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.quality.dto.QualityRunReportDTO;
import com.bigdata.modules.quality.entity.DqRunInstance;
import com.bigdata.modules.quality.service.DqRunService;
import com.bigdata.modules.quality.vo.QualityRuleResultVO;
import com.bigdata.modules.quality.vo.QualityRunInstanceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 质检运行记录接口契约回归。
 *
 * <p>锁三件事，任何一件被破坏都会让前端 {@code src/api/quality.ts} 静默失效：</p>
 * <ol>
 *   <li><b>路由可达</b>：{@code /quality/run} 四条映射与前端 {@code qualityApi} 逐一对应
 *       （控制器不写 {@code /api} 前缀，由 {@code server.servlet.context-path} 提供）；</li>
 *   <li><b>字段命名 = camelCase</b>：服务端由 {@code WebMvcConfig} 的 {@code @Primary}
 *       {@code ObjectMapper} 序列化，{@code application.yml} 的
 *       {@code property-naming-strategy: SNAKE_CASE} <b>不生效</b>（见服务端 README 注意事项 4），
 *       所以用 {@link ContractMockMvcSupport#productionMockMvc} 而非按 yml 语义构造的 {@code mockMvc(...)}；</li>
 *   <li><b>回传入参绑定</b>：S3 侧质检程序按「质检结果回传规范」逐字回传的字段名必须能绑上 DTO，
 *       尤其是后加的 {@code ruleId} 与 {@code execSql} —— 字段名写错不会编译失败，只会静默为 null。</li>
 * </ol>
 *
 * <p>不启动 Spring 容器、不连库：只测「映射 + 参数绑定 + 序列化」这一段。
 * 落库语义（rule_id 反查、exec_sql 入库）见 {@code DqRunServiceReportTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检运行记录接口契约：路由可达 + camelCase 字段名（对齐前端 quality.ts）")
class DqRunApiContractTest {

    /** snake_case 字段名探测（如 check_record_count / schedule_instance_id / exec_sql） */
    private static final Pattern SNAKE_KEY = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private final ObjectMapper mapper = ContractMockMvcSupport.productionObjectMapper();

    @Mock
    private DqRunService dqRunService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DqRunController controller = new DqRunController();
        ReflectionTestUtils.setField(controller, "dqRunService", dqRunService);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 前端 quality.ts 在 /quality/run 下的每一次调用，逐条对位 */
    private static List<MockHttpServletRequestBuilder> frontendCalls() {
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        calls.add(get("/quality/run?current=1&size=20"));
        calls.add(get("/quality/run/1"));
        calls.add(get("/quality/run/1/results"));
        // 程序回传（非界面调用）：字段名即 S3 提示词里的回传规范
        calls.add(post("/quality/run/report").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceName\":\"hive-prod\",\"databaseName\":\"test_db\","
                        + "\"tableName\":\"dwd_event_f\",\"batchNo\":\"20261006\","
                        + "\"ruleId\":21,\"ruleName\":\"R_AD_001 事件主键唯一\","
                        + "\"ruleType\":\"PRIMARY_KEY_UNIQUE\",\"passed\":false,"
                        + "\"execSql\":\"SELECT 1\"}"));
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

    /** 递归断言返回体中不存在 snake_case 键（值不检查） */
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
                        "接口返回字段名出现 snake_case：" + path + "." + key
                                + " —— 生产由 WebMvcConfig 的 @Primary ObjectMapper 序列化为 camelCase，"
                                + "前端 src/api 的类型定义按 camelCase 编写（见 README 注意事项 4）");
                assertNoSnakeCaseKey(entry.getValue(), path + "." + key);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoSnakeCaseKey(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private QualityRunInstanceVO instanceVO() {
        QualityRunInstanceVO vo = new QualityRunInstanceVO();
        vo.setId(1L);
        vo.setTaskId(9L);
        vo.setTaskName("广告域质检");
        vo.setBatchNo("20261006");
        vo.setTriggerType("MANUAL");
        vo.setScheduleInstanceId(12345L);
        vo.setStartTime(LocalDateTime.of(2026, 10, 6, 2, 0));
        vo.setEndTime(LocalDateTime.of(2026, 10, 6, 2, 3));
        vo.setStatus("SUCCESS");
        vo.setCheckRecordCount(1000L);
        vo.setFailRecordCount(3L);
        vo.setPassCount(5);
        vo.setWarnCount(0);
        vo.setErrorCount(1);
        vo.setFailedCount(0);
        return vo;
    }

    private QualityRuleResultVO resultVO() {
        QualityRuleResultVO vo = new QualityRuleResultVO();
        vo.setId(11L);
        vo.setRunId(1L);
        vo.setRuleId(21L);
        vo.setRuleName("R_AD_001 事件主键唯一");
        vo.setRuleType("PRIMARY_KEY_UNIQUE");
        vo.setCheckObject("test_db.dwd_event_f.event_id");
        vo.setThreshold("重复=0");
        vo.setActualValue("重复 3 条");
        vo.setStatus("ERROR");
        vo.setSeverity("ERROR");
        vo.setExecSql("SELECT event_id FROM test_db.dwd_event_f GROUP BY event_id HAVING COUNT(1) > 1");
        vo.setErrorMsg("主键重复");
        vo.setCostMs(120L);
        return vo;
    }

    // ==================================================================
    // 1. 路由覆盖
    // ==================================================================

    @Nested
    @DisplayName("路由可达性")
    class RouteCoverage {

        @Test
        @DisplayName("前端已接通的 4 个调用全部命中，无 404 / 405")
        void allFrontendCallsAreMapped() throws Exception {
            when(dqRunService.getById(1L)).thenReturn(new DqRunInstance());
            Page<QualityRunInstanceVO> page = new Page<>(1, 20);
            page.setRecords(List.of(instanceVO()));
            when(dqRunService.page(anyInt(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(page);
            when(dqRunService.listResults(1L)).thenReturn(List.of(resultVO()));

            List<MockHttpServletRequestBuilder> calls = frontendCalls();
            assertEquals(4, calls.size(),
                    "路由表条数必须与前端 quality.ts 中 /quality/run 下的调用数一致；新增接口时请同步本表");

            for (MockHttpServletRequestBuilder builder : calls) {
                mockMvc.perform(builder).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("未映射的子路径应 404，防止通配映射掩盖拼写错误")
        void unknownSubPathIsNotFound() throws Exception {
            mockMvc.perform(get("/quality/run/1/unknown")).andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // 2. 字段命名
    // ==================================================================

    @Nested
    @DisplayName("字段命名（camelCase）")
    class FieldNaming {

        @Test
        @DisplayName("运行实例列表不出现 snake_case，且调度实例号 / 计数列为 camelCase")
        void runInstanceFieldsAreCamelCase() throws Exception {
            Page<QualityRunInstanceVO> page = new Page<>(1, 20);
            page.setRecords(List.of(instanceVO()));
            when(dqRunService.page(anyInt(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            JsonNode data = dataOf(get("/quality/run?current=1&size=20"));
            assertNoSnakeCaseKey(data, "data");

            JsonNode record = data.get("records").get(0);
            assertEquals(12345L, record.get("scheduleInstanceId").asLong());
            assertEquals(1000L, record.get("checkRecordCount").asLong());
            assertEquals(3L, record.get("failRecordCount").asLong());
            assertEquals("MANUAL", record.get("triggerType").asText());
        }

        @Test
        @DisplayName("规则级结果含 execSql / ruleId，且不出现 snake_case")
        void ruleResultFieldsAreCamelCase() throws Exception {
            when(dqRunService.getById(1L)).thenReturn(new DqRunInstance());
            when(dqRunService.listResults(1L)).thenReturn(List.of(resultVO()));

            JsonNode data = dataOf(get("/quality/run/1/results"));
            assertNoSnakeCaseKey(data, "data");

            JsonNode first = data.get(0);
            assertEquals(21L, first.get("ruleId").asLong());
            assertEquals("R_AD_001 事件主键唯一", first.get("ruleName").asText());
            assertTrue(first.get("execSql").asText().startsWith("SELECT event_id"));
            assertEquals(120L, first.get("costMs").asLong());
        }
    }

    // ==================================================================
    // 3. 回传入参绑定
    // ==================================================================

    @Nested
    @DisplayName("回传入参绑定")
    class ReportBinding {

        @Test
        @DisplayName("回传字段按 camelCase 绑定到 DTO，ruleId 与 execSql 不得静默丢失")
        void reportBindsAllDocumentedFields() throws Exception {
            JsonNode body = call(post("/quality/run/report").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"datasourceId\":7,\"datasourceName\":\"hive-prod\","
                            + "\"databaseName\":\"test_db\",\"tableName\":\"dwd_event_f\","
                            + "\"batchNo\":\"20261006\",\"ruleId\":21,"
                            + "\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                            + "\"checkObject\":\"test_db.dwd_event_f.event_id\",\"threshold\":\"重复=0\","
                            + "\"actualValue\":\"重复 3 条\",\"passed\":false,"
                            + "\"checkRecordCount\":1000,\"failRecordCount\":3,"
                            + "\"failDetail\":\"主键重复\",\"execSql\":\"SELECT 1\"}"));
            assertEquals(200, body.get("code").asInt());

            ArgumentCaptor<QualityRunReportDTO> captor = ArgumentCaptor.forClass(QualityRunReportDTO.class);
            verify(dqRunService).reportRun(captor.capture());
            QualityRunReportDTO dto = captor.getValue();

            assertEquals(7L, dto.getDatasourceId());
            assertEquals("hive-prod", dto.getDatasourceName());
            assertEquals("test_db", dto.getDatabaseName());
            assertEquals("dwd_event_f", dto.getTableName());
            assertEquals("20261006", dto.getBatchNo());
            assertEquals("R_AD_001 事件主键唯一", dto.getRuleName());
            assertEquals("PRIMARY_KEY_UNIQUE", dto.getRuleType());
            assertEquals("test_db.dwd_event_f.event_id", dto.getCheckObject());
            assertEquals("重复=0", dto.getThreshold());
            assertEquals("重复 3 条", dto.getActualValue());
            assertEquals(Boolean.FALSE, dto.getPassed());
            assertEquals(1000L, dto.getCheckRecordCount());
            assertEquals(3L, dto.getFailRecordCount());
            assertEquals("主键重复", dto.getFailDetail());
            // 后加的两个字段：漏绑不会编译失败，只会静默为 null
            assertEquals(21L, dto.getRuleId(), "ruleId 必须能绑上，否则规则关联退回字符串匹配");
            assertEquals("SELECT 1", dto.getExecSql(), "execSql 必须能绑上，否则无法事后核对当时跑的 SQL");
        }

        @Test
        @DisplayName("ruleId 与 execSql 可选：不传时仍能回传成功")
        void optionalFieldsMayBeAbsent() throws Exception {
            JsonNode body = call(post("/quality/run/report").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"datasourceName\":\"hive-prod\",\"databaseName\":\"test_db\","
                            + "\"tableName\":\"dwd_event_f\",\"batchNo\":\"20261006\","
                            + "\"ruleName\":\"R_AD_001 事件主键唯一\","
                            + "\"ruleType\":\"PRIMARY_KEY_UNIQUE\",\"passed\":true}"));
            assertEquals(200, body.get("code").asInt());

            ArgumentCaptor<QualityRunReportDTO> captor = ArgumentCaptor.forClass(QualityRunReportDTO.class);
            verify(dqRunService).reportRun(captor.capture());
            assertNull(captor.getValue().getRuleId());
            assertNull(captor.getValue().getExecSql());
            assertEquals(Boolean.TRUE, captor.getValue().getPassed());
        }

        @Test
        @DisplayName("业务异常走 HTTP 200 + body.code（前端 request 靠 body.code 判错）")
        void businessExceptionKeepsHttp200() throws Exception {
            org.mockito.Mockito.doThrow(new BusinessException("上报内容缺少规则名"))
                    .when(dqRunService).reportRun(any(QualityRunReportDTO.class));

            JsonNode body = call(post("/quality/run/report").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ruleName\":\"\"}"));
            assertFalse(body.get("code").asInt() == 200, "业务异常应携带真实错误码");
            assertTrue(body.get("message").asText().contains("规则名"));
        }
    }
}
