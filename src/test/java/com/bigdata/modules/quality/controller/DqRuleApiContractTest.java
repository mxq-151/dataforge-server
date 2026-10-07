package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.quality.dto.QualityRuleRegisterDTO;
import com.bigdata.modules.quality.service.DqRuleService;
import com.bigdata.modules.quality.vo.QualityRuleRegisterVO;
import com.bigdata.modules.quality.vo.QualityRuleVO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监控规则接口契约回归（含规则登记）。
 *
 * <p>锁三件事：</p>
 * <ol>
 *   <li><b>路由可达</b>：{@code /quality/rule} 七条映射与前端 {@code qualityApi} 及 S3 程序回传约定对应；</li>
 *   <li><b>字段命名 = camelCase</b>：由 {@code WebMvcConfig} 的 {@code @Primary ObjectMapper} 序列化，
 *       yml 里的 SNAKE_CASE 不生效（见服务端 README 注意事项 4）；</li>
 *   <li><b>登记入参绑定</b>：S3 侧程序按「规则登记规范」逐字提交的字段必须能绑上 DTO。
 *       字段名写错不会编译失败，只会静默为 null —— 那样登记出来的规则就缺库表，</li>
 * </ol>
 *
 * <p>登记本身的幂等语义（已存在复用不覆盖 / 不存在创建并绑模板）见 {@code DqRuleRegisterTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("监控规则接口契约：路由可达 + camelCase 字段名 + 登记入参绑定")
class DqRuleApiContractTest {

    /** snake_case 字段名探测（如 ref_database_name / warn_threshold / template_code） */
    private static final Pattern SNAKE_KEY = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private final ObjectMapper mapper = ContractMockMvcSupport.productionObjectMapper();

    @Mock
    private DqRuleService dqRuleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DqRuleController controller = new DqRuleController();
        ReflectionTestUtils.setField(controller, "dqRuleService", dqRuleService);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 前端 quality.ts 与 S3 程序在本控制器下的每一次调用，逐条对位 */
    private static List<MockHttpServletRequestBuilder> frontendCalls() {
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        calls.add(get("/quality/rule?current=1&size=10"));
        calls.add(get("/quality/rule?ruleName=R_AD_001%20%E4%BA%8B%E4%BB%B6%E4%B8%BB%E9%94%AE%E5%94%AF%E4%B8%80"));
        calls.add(post("/quality/rule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                        + "\"tableName\":\"dwd_event_f\"}"));
        calls.add(put("/quality/rule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"ruleName\":\"R_AD_001 事件主键唯一\","
                        + "\"ruleType\":\"PRIMARY_KEY_UNIQUE\",\"tableName\":\"dwd_event_f\"}"));
        calls.add(delete("/quality/rule/1"));
        calls.add(post("/quality/rule/test-run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                        + "\"tableName\":\"dwd_event_f\"}"));
        calls.add(post("/quality/rule/batch-by-template").contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":1,\"targets\":[{\"datasourceId\":7,\"databaseName\":\"test_db\","
                        + "\"tableName\":\"dwd_event_f\"}],\"defaults\":{}}"));
        // 规则登记（S3 程序调用，非界面）
        calls.add(post("/quality/rule/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                        + "\"datasourceName\":\"hive-prod\",\"databaseName\":\"test_db\","
                        + "\"tableName\":\"dwd_event_f\",\"columnName\":\"event_id\","
                        + "\"expectedValue\":\"重复=0\",\"disposeMode\":\"BLOCK\"}"));
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

    private QualityRuleVO ruleVO() {
        QualityRuleVO vo = new QualityRuleVO();
        vo.setId(1L);
        vo.setRuleName("R_AD_001 事件主键唯一");
        vo.setRuleType("PRIMARY_KEY_UNIQUE");
        vo.setTemplateId(11L);
        vo.setTemplateCode("PRIMARY_KEY_UNIQUE");
        vo.setDatasourceId(7L);
        vo.setDatasourceName("hive-prod");
        vo.setDatabaseName("test_db");
        vo.setTableName("dwd_event_f");
        vo.setColumnName("event_id");
        vo.setRefDatasourceId(8L);
        vo.setRefDatabaseName("test_db");
        vo.setRefTableName("dim_creative_f");
        vo.setRefColumnName("ad_id");
        vo.setCompareMode("EQ");
        vo.setExpectedValue("重复=0");
        vo.setWarnThreshold("重复<=1");
        vo.setErrorThreshold("重复>0");
        vo.setDisposeMode("BLOCK");
        vo.setOwnerName("zhangsan");
        vo.setEnabled(true);
        vo.setCreateTime(LocalDateTime.of(2026, 10, 7, 8, 0));
        vo.setUpdateTime(LocalDateTime.of(2026, 10, 7, 8, 0));
        return vo;
    }

    private void stubPage() {
        Page<QualityRuleVO> page = new Page<>(1, 10);
        page.setRecords(List.of(ruleVO()));
        when(dqRuleService.page(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);
    }

    // ==================================================================
    // 1. 路由覆盖
    // ==================================================================

    @Nested
    @DisplayName("路由可达性")
    class RouteCoverage {

        @Test
        @DisplayName("前端与 S3 程序已接通的 8 个调用全部命中，无 404 / 405")
        void allFrontendCallsAreMapped() throws Exception {
            stubPage();
            when(dqRuleService.register(any(QualityRuleRegisterDTO.class))).thenReturn(new QualityRuleRegisterVO());

            List<MockHttpServletRequestBuilder> calls = frontendCalls();
            assertEquals(8, calls.size(),
                    "路由表条数必须与前端 quality.ts / 登记规范中已接通的调用数一致；新增接口时请同步本表");

            for (MockHttpServletRequestBuilder builder : calls) {
                mockMvc.perform(builder).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("未映射的子路径应 404，防止通配映射掩盖拼写错误")
        void unknownSubPathIsNotFound() throws Exception {
            mockMvc.perform(get("/quality/rule/1/unknown")).andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // 2. 字段命名
    // ==================================================================

    @Nested
    @DisplayName("字段命名（camelCase）")
    class FieldNaming {

        @Test
        @DisplayName("规则列表不出现 snake_case，且跨表 / 阈值列均为 camelCase")
        void ruleFieldsAreCamelCase() throws Exception {
            stubPage();

            JsonNode data = dataOf(get("/quality/rule?current=1&size=10"));
            assertNoSnakeCaseKey(data, "data");

            JsonNode first = data.get("records").get(0);
            assertEquals("test_db", first.get("refDatabaseName").asText());
            assertEquals("dim_creative_f", first.get("refTableName").asText());
            assertEquals("ad_id", first.get("refColumnName").asText());
            assertEquals("重复<=1", first.get("warnThreshold").asText());
            assertEquals("PRIMARY_KEY_UNIQUE", first.get("templateCode").asText());
        }

        @Test
        @DisplayName("登记回执字段为 camelCase（ruleId / templateCode / created）")
        void registerReceiptFieldsAreCamelCase() throws Exception {
            QualityRuleRegisterVO vo = new QualityRuleRegisterVO();
            vo.setRuleId(21L);
            vo.setRuleName("R_AD_001 事件主键唯一");
            vo.setRuleType("PRIMARY_KEY_UNIQUE");
            vo.setTemplateCode("PRIMARY_KEY_UNIQUE");
            vo.setCreated(false);
            when(dqRuleService.register(any(QualityRuleRegisterDTO.class))).thenReturn(vo);

            JsonNode data = dataOf(post("/quality/rule/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                            + "\"tableName\":\"dwd_event_f\"}"));
            assertNoSnakeCaseKey(data, "data");

            assertEquals(21L, data.get("ruleId").asLong());
            assertEquals("PRIMARY_KEY_UNIQUE", data.get("templateCode").asText());
            assertFalse(data.get("created").asBoolean());
        }
    }

    // ==================================================================
    // 3. 入参绑定与错误包络
    // ==================================================================

    @Nested
    @DisplayName("登记入参绑定与错误语义")
    class RegisterBinding {

        @Test
        @DisplayName("登记字段按 camelCase 绑定，库表字段名不得静默丢失")
        void registerBindsRuleIdentity() throws Exception {
            when(dqRuleService.register(any(QualityRuleRegisterDTO.class))).thenReturn(new QualityRuleRegisterVO());

            call(post("/quality/rule/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ruleName\":\"R_AD_001 事件主键唯一\",\"ruleType\":\"PRIMARY_KEY_UNIQUE\","
                            + "\"datasourceName\":\"hive-prod\",\"databaseName\":\"test_db\","
                            + "\"tableName\":\"dwd_event_f\",\"columnName\":\"event_id\","
                            + "\"expectedValue\":\"重复=0\",\"warnThreshold\":\"重复<=1\","
                            + "\"disposeMode\":\"BLOCK\",\"ownerName\":\"zhangsan\"}"));

            ArgumentCaptor<QualityRuleRegisterDTO> captor = ArgumentCaptor.forClass(QualityRuleRegisterDTO.class);
            verify(dqRuleService).register(captor.capture());
            QualityRuleRegisterDTO dto = captor.getValue();

            assertEquals("R_AD_001 事件主键唯一", dto.getRuleName());
            assertEquals("PRIMARY_KEY_UNIQUE", dto.getRuleType());
            assertEquals("hive-prod", dto.getDatasourceName());
            assertEquals("test_db", dto.getDatabaseName(), "库名是登记判定键的一半，漏绑会登记出孤儿规则");
            assertEquals("dwd_event_f", dto.getTableName(), "表名是登记判定键的另一半");
            assertEquals("event_id", dto.getColumnName());
            assertEquals("重复=0", dto.getExpectedValue());
            assertEquals("重复<=1", dto.getWarnThreshold());
            assertEquals("BLOCK", dto.getDisposeMode());
        }

        @Test
        @DisplayName("ruleName 过滤参数可下推（登记前查「这条规则在不在平台」）")
        void ruleNameFilterIsPassedThrough() throws Exception {
            stubPage();

            call(get("/quality/rule").param("ruleName", "R_AD_001 事件主键唯一"));

            // enabled 未传时为 null，这里不能用 anyBoolean()（它不匹配 null）
            verify(dqRuleService).page(anyInt(), anyInt(), any(), any(), any(), any(), any(),
                    any(), eq("R_AD_001 事件主键唯一"));
        }

        @Test
        @DisplayName("业务异常走 HTTP 200 + body.code（前端 request 靠 body.code 判错）")
        void businessExceptionKeepsHttp200() throws Exception {
            when(dqRuleService.register(any(QualityRuleRegisterDTO.class)))
                    .thenThrow(new BusinessException(400, "登记必须提供规则名"));

            JsonNode body = call(post("/quality/rule/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ruleType\":\"PRIMARY_KEY_UNIQUE\"}"));

            assertEquals(400, body.get("code").asInt());
            assertTrue(body.get("message").asText().contains("规则名"));
        }
    }
}
