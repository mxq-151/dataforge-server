package com.bigdata.modules.asset.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.exception.GlobalExceptionHandler;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.asset.service.AssetCatalogService;
import com.bigdata.modules.asset.service.AssetGovernanceService;
import com.bigdata.modules.asset.service.AssetInventoryService;
import com.bigdata.modules.asset.service.AssetMetaService;
import com.bigdata.modules.asset.service.AssetProducerService;
import com.bigdata.modules.asset.service.SchedulerTaskMetricsService;
import com.bigdata.modules.asset.task.AssetCatalogSnapshotTask;
import com.bigdata.modules.asset.task.SchedulerTaskMetricsCollectTask;
import com.bigdata.modules.asset.vo.AssetCategoryVO;
import com.bigdata.modules.asset.vo.AssetColumnVO;
import com.bigdata.modules.asset.vo.AssetDocHistoryVO;
import com.bigdata.modules.asset.vo.AssetDocVO;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.asset.vo.AssetIndicatorVO;
import com.bigdata.modules.asset.vo.AssetInventoryVO;
import com.bigdata.modules.asset.vo.AssetPreviewVO;
import com.bigdata.modules.asset.vo.AssetProducerVO;
import com.bigdata.modules.asset.vo.AssetQualitySummaryVO;
import com.bigdata.modules.asset.vo.AssetSearchHitVO;
import com.bigdata.modules.asset.vo.AssetSearchResultVO;
import com.bigdata.modules.asset.vo.AssetTableDetailVO;
import com.bigdata.modules.asset.vo.AssetTagVO;
import com.bigdata.modules.asset.vo.AssetTaskDelayVO;
import com.bigdata.modules.asset.vo.CatalogTreeNodeVO;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据资产目录接口契约回归。
 *
 * <p>锁定三件事，任何一件被破坏都会让前端 {@code src/api/assetCatalog.ts} 静默失效：</p>
 * <ol>
 *   <li><b>路由可达</b>：30 条映射与前端已接通的调用逐一对应（少写 /api 前缀、拼错子路径都会 404）；</li>
 *   <li><b>字段命名 = camelCase</b>：服务端返回体由 {@code WebMvcConfig} 的 {@code @Primary}
 *       {@code ObjectMapper} 序列化，{@code application.yml} 里的
 *       {@code property-naming-strategy: SNAKE_CASE} <b>不生效</b>（见服务端 README 注意事项 4）。
 *       因此这里用 {@link ContractMockMvcSupport#productionMockMvc}，而不是按 yml 语义构造的
 *       {@code mockMvc(...)} —— 后者会断言出线上并不存在的 {@code node_kind} 这类字段名。</li>
 *   <li><b>入参默认值与错误语义</b>：缺省分页 / days / limit 兜底，业务异常走 HTTP 200 + body.code。</li>
 * </ol>
 *
 * <p>不启动 Spring 容器、不连库：只测「映射 + 参数绑定 + 序列化」这一段。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("资产目录接口契约：路由可达 + camelCase 字段名（对齐前端 assetCatalog.ts）")
class AssetCatalogApiContractTest {

    /** snake_case 字段名探测（如 node_kind / table_metadata_id / browse_cnt_30d） */
    private static final Pattern SNAKE_KEY = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

    private final ObjectMapper mapper = ContractMockMvcSupport.productionObjectMapper();

    @Mock
    private AssetCatalogService assetCatalogService;
    @Mock
    private AssetGovernanceService assetGovernanceService;
    @Mock
    private AssetMetaService assetMetaService;
    @Mock
    private AssetInventoryService assetInventoryService;
    @Mock
    private AssetProducerService assetProducerService;
    @Mock
    private SchedulerTaskMetricsService schedulerTaskMetricsService;
    @Mock
    private AssetCatalogSnapshotTask catalogSnapshotTask;
    @Mock
    private SchedulerTaskMetricsCollectTask schedulerMetricsTask;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AssetCatalogController controller = new AssetCatalogController();
        ReflectionTestUtils.setField(controller, "assetCatalogService", assetCatalogService);
        ReflectionTestUtils.setField(controller, "assetGovernanceService", assetGovernanceService);
        ReflectionTestUtils.setField(controller, "assetMetaService", assetMetaService);
        ReflectionTestUtils.setField(controller, "assetInventoryService", assetInventoryService);
        ReflectionTestUtils.setField(controller, "assetProducerService", assetProducerService);
        ReflectionTestUtils.setField(controller, "schedulerTaskMetricsService", schedulerTaskMetricsService);
        ReflectionTestUtils.setField(controller, "catalogSnapshotTask", catalogSnapshotTask);
        ReflectionTestUtils.setField(controller, "schedulerMetricsTask", schedulerMetricsTask);
        mockMvc = ContractMockMvcSupport.productionMockMvc(controller);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 前端 assetCatalog.ts 的 24 个调用，逐条对位 */
    private static List<MockHttpServletRequestBuilder> frontendCalls() {
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        // 目录与检索
        calls.add(get("/asset/catalog/tree"));
        calls.add(get("/asset/search"));
        calls.add(get("/asset/suggest"));
        // 详情与页签
        calls.add(get("/asset/table/1"));
        calls.add(get("/asset/table/1/producers"));
        calls.add(get("/asset/table/1/indicators"));
        calls.add(get("/asset/table/1/quality"));
        calls.add(get("/asset/table/1/preview"));
        // 治理
        calls.add(put("/asset/table/1/owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessOwner\":\"zhangsan\",\"techOwner\":\"lisi\"}"));
        calls.add(put("/asset/table/1/category").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryCode\":\"trade.order\",\"visibility\":\"internal\"}"));
        calls.add(put("/asset/table/1/lifecycle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hotDays\":90,\"coldDays\":180,\"keepDays\":720}"));
        calls.add(put("/asset/table/1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCodes\":[\"core\"]}"));
        calls.add(get("/asset/table/1/doc"));
        calls.add(put("/asset/table/1/doc").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# 表说明\"}"));
        calls.add(get("/asset/table/1/doc/history"));
        calls.add(post("/asset/table/1/refresh"));
        // 字典
        calls.add(get("/asset/tag"));
        calls.add(get("/asset/category"));
        // 盘点
        calls.add(get("/asset/inventory/overview"));
        calls.add(get("/asset/inventory/health"));
        // 资产概况看板：五个看板均已接入（top-compute / task-delay 的数据由
        // SchedulerTaskMetricsCollectTask 每日从 DolphinScheduler 采集落库）
        calls.add(get("/asset/inventory/env-project"));
        calls.add(get("/asset/inventory/top-assets?limit=100"));
        calls.add(get("/asset/inventory/top-compute?sortBy=resource&limit=50"));
        calls.add(get("/asset/inventory/task-delay?days=30"));
        calls.add(get("/asset/inventory/size-growth?growthPercent=10&minSizeBytes=10737418240"));
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

    // ==================================================================
    // 1. 路由覆盖
    // ==================================================================

    @Nested
    @DisplayName("路由可达性")
    class RouteCoverage {

        @Test
        @DisplayName("前端已接通的 25 个调用全部命中，无 404 / 405")
        void allFrontendCallsAreMapped() throws Exception {
            List<MockHttpServletRequestBuilder> calls = frontendCalls();
            assertEquals(25, calls.size(),
                    "路由表条数必须与前端 assetCatalog.ts 中已接通的调用数一致；新增接口时请同步本表");

            for (MockHttpServletRequestBuilder builder : calls) {
                mockMvc.perform(builder).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("未映射的子路径仍返回 404（避免用通配映射掩盖拼写错误）")
        void unmappedPathStill404() throws Exception {
            mockMvc.perform(get("/asset/table/1/not-a-real-tab"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // 2. 返回体包络
    // ==================================================================

    @Nested
    @DisplayName("返回体包络")
    class Envelope {

        @Test
        @DisplayName("成功响应固定为 code/message/data 三键")
        void successEnvelopeShape() throws Exception {
            when(assetMetaService.listTags()).thenReturn(List.of());

            JsonNode body = call(get("/asset/tag"));
            assertEquals(200, body.get("code").asInt());
            assertNotNull(body.get("message"));
            assertTrue(body.has("data"));
        }

        @Test
        @DisplayName("写接口无返回数据时 data 为 null（Result.success()）")
        void voidDataIsNull() throws Exception {
            JsonNode body = call(post("/asset/table/1/refresh"));
            assertEquals(200, body.get("code").asInt());
            assertTrue(body.get("data").isNull());
        }

        @Test
        @DisplayName("业务异常：HTTP 200 + body.code 携带真实错误码（前端按 code 判错）")
        void businessExceptionKeepsHttp200() throws Exception {
            when(assetCatalogService.detail(999L)).thenThrow(new BusinessException(404, "资产不存在"));

            JsonNode body = call(get("/asset/table/999"));
            assertEquals(404, body.get("code").asInt(),
                    "BusinessException 由 GlobalExceptionHandler 转成 HTTP 200 + body.code，"
                            + "前端 request 依赖 body.code 判错");
            assertEquals("资产不存在", body.get("message").asText());
        }

        @Test
        @DisplayName("权限失败：432/403 等业务码原样透出，不退化为 500")
        void permissionFailureKeepsCode() throws Exception {
            when(assetGovernanceService.getDoc(9L)).thenThrow(new BusinessException(403, "无权访问该资产"));

            JsonNode body = call(get("/asset/table/9/doc"));
            assertEquals(403, body.get("code").asInt());
        }
    }

    // ==================================================================
    // 3. 字段命名（camelCase）
    // ==================================================================

    @Nested
    @DisplayName("字段命名 camelCase")
    class FieldNaming {

        @Test
        @DisplayName("目录树：engine 视角节点用 nodeKind / tableMetadataId / unclassified")
        void catalogTreeKeys() throws Exception {
            when(assetCatalogService.tree("engine", 1L, "dw", null, null)).thenReturn(List.of(treeRoot()));

            JsonNode data = dataOf(get("/asset/catalog/tree?view=engine&datasourceId=1&databaseName=dw"));
            assertNoSnakeCaseKey(data, "$");

            JsonNode ds = data.get(0);
            assertEquals("datasource", ds.get("nodeKind").asText());
            assertEquals(1L, ds.get("datasourceId").asLong());
            assertEquals(1, ds.get("assetCount").asInt());

            JsonNode tbl = ds.get("children").get(0).get("children").get(0);
            assertEquals("table", tbl.get("nodeKind").asText());
            assertEquals(101L, tbl.get("tableMetadataId").asLong());
            assertEquals("dwd", tbl.get("layerCode").asText());
            assertEquals("order", tbl.get("domainCode").asText());
            assertTrue(tbl.get("unclassified").asBoolean(),
                    "解析失败的分层/业务域节点必须带 unclassified 供前端标记待治理");
            assertEquals("hive", tbl.get("storageEngine").asText());
        }

        @Test
        @DisplayName("表详情：治理字段 + 列级标记均为 camelCase（businessOwner/isPartition 等）")
        void tableDetailKeys() throws Exception {
            when(assetCatalogService.detail(101L)).thenReturn(detail());

            JsonNode data = dataOf(get("/asset/table/101"));
            assertNoSnakeCaseKey(data, "$");

            // 这几个字段名前缀相同但语义不同，最容易被"顺手写成 snake_case"
            assertEquals("zhangsan", data.get("businessOwner").asText());
            assertEquals("lisi", data.get("techOwner").asText());
            assertEquals("dwd", data.get("layerCode").asText());
            assertEquals("order", data.get("domainCode").asText());
            assertTrue(data.get("conventionParsed").asBoolean());
            assertEquals(2, data.get("healthIssueCount").asInt());

            JsonNode col = data.get("columns").get(0);
            // Boolean 包装类型的 getter 是 getIsPartition()，Jackson 属性名必须是 isPartition
            assertFalse(col.get("isPartition").asBoolean());
            assertTrue(col.get("isPrimaryKey").asBoolean());
            assertFalse(col.get("nullable").asBoolean());
            assertEquals("L2", col.get("securityLevel").asText());

            assertEquals("core", data.get("tags").get(0).get("tagCode").asText());
        }

        @Test
        @DisplayName("检索结果：tables/columns/indicators 三组命中共用同一 hit 结构")
        void searchKeys() throws Exception {
            when(assetCatalogService.search("order", "table,column,indicator", 1, 20, "dwd", null, null))
                    .thenReturn(searchResult());

            JsonNode data = dataOf(get("/asset/search?keyword=order&types=table,column,indicator"
                    + "&current=1&size=20&layerCode=dwd"));
            assertNoSnakeCaseKey(data, "$");

            assertEquals(3, data.get("total").asInt());
            JsonNode hit = data.get("tables").get(0);
            assertEquals("table", hit.get("assetType").asText());
            assertEquals(101L, hit.get("tableMetadataId").asLong());
            assertNotNull(hit.get("matchedText"));
            assertEquals("core", data.get("columns").get(0).get("layerCode").asText());
            assertEquals("IND_ORDER_AMT", data.get("indicators").get(0).get("title").asText());
        }

        @Test
        @DisplayName("质量概览 / 数据预览 / 产出任务 / 关联指标 字段名")
        void otherTabKeys() throws Exception {
            when(assetCatalogService.quality(101L)).thenReturn(quality());
            when(assetCatalogService.preview(101L, 20, "dt=20260916")).thenReturn(preview());
            when(assetProducerService.producers(101L)).thenReturn(List.of(producer()));
            when(assetCatalogService.indicators(101L)).thenReturn(List.of(indicator()));

            JsonNode q = dataOf(get("/asset/table/101/quality"));
            assertNoSnakeCaseKey(q, "$");
            assertEquals(6, q.get("ruleCount").asInt());
            assertEquals(5, q.get("enabledRuleCount").asInt());
            assertEquals(1, q.get("problemRuleCount").asInt());
            assertEquals("FAILED", q.get("lastRunStatus").asText());

            JsonNode p = dataOf(get("/asset/table/101/preview").param("limit", "20")
                    .param("partition", "dt=20260916"));
            assertNoSnakeCaseKey(p, "$");
            assertEquals(List.of("order_id", "amt"), mapper.convertValue(p.get("columns"), List.class));

            JsonNode prod = dataOf(get("/asset/table/101/producers"));
            assertNoSnakeCaseKey(prod, "$");
            assertEquals("10001", prod.get(0).get("workflowCode").asText());
            assertEquals("dwd_order_detail_di", prod.get(0).get("nodeName").asText());
            assertEquals("SUCCESS", prod.get(0).get("lastRunState").asText());

            JsonNode ind = dataOf(get("/asset/table/101/indicators"));
            assertNoSnakeCaseKey(ind, "$");
            assertEquals("IND_ORDER_AMT", ind.get(0).get("code").asText());
            assertEquals("order_id", ind.get(0).get("fieldName").asText());
        }

        @Test
        @DisplayName("盘点 / 治理项 / 字典 字段名")
        void inventoryAndMetaKeys() throws Exception {
            when(assetInventoryService.overview("proj_a", null, null, null)).thenReturn(inventory());
            when(assetInventoryService.health("proj_a", null, null, null)).thenReturn(List.of(healthIssue()));
            when(assetMetaService.listCategories()).thenReturn(List.of(category()));
            when(assetGovernanceService.getDoc(101L)).thenReturn(doc());
            when(assetGovernanceService.listDocHistory(101L)).thenReturn(List.of(docHistory()));

            JsonNode inv = dataOf(get("/asset/inventory/overview?projectCode=proj_a"));
            assertNoSnakeCaseKey(inv, "$");
            assertEquals(120, inv.get("totalTables").asInt());
            assertEquals(5000000000L, inv.get("totalSizeBytes").asLong());
            assertEquals(7, inv.get("unclassifiedCount").asInt());
            assertEquals("dwd", inv.get("byLayer").get(0).get("layerCode").asText());

            JsonNode health = dataOf(get("/asset/inventory/health?projectCode=proj_a"));
            assertNoSnakeCaseKey(health, "$");
            assertEquals("NO_TABLE_COMMENT", health.get(0).get("issueCode").asText());
            assertEquals(101L, health.get(0).get("samples").get(0).get("tableMetadataId").asLong());

            JsonNode cat = dataOf(get("/asset/category"));
            assertNoSnakeCaseKey(cat, "$");
            assertEquals("trade.order", cat.get(0).get("code").asText());
            assertEquals("trade", cat.get(0).get("parentCode").asText());
            assertEquals("domain", cat.get(0).get("categoryKind").asText());

            JsonNode doc = dataOf(get("/asset/table/101/doc"));
            assertNoSnakeCaseKey(doc, "$");
            assertEquals("# 表说明", doc.get("content").asText());
            assertEquals("zhangsan", doc.get("updateBy").asText());

            JsonNode history = dataOf(get("/asset/table/101/doc/history"));
            assertNoSnakeCaseKey(history, "$");
            assertEquals(2, history.get(0).get("version").asInt());
        }
    }

    // ==================================================================
    // 4. 入参绑定与默认值
    // ==================================================================

    @Nested
    @DisplayName("入参绑定与默认值")
    class QueryBinding {

        @Test
        @DisplayName("目录树缺省 view=engine，其余参数为 null")
        void treeDefaults() throws Exception {
            mockMvc.perform(get("/asset/catalog/tree")).andExpect(status().isOk());
            verify(assetCatalogService).tree("engine", null, null, null, null);
        }

        @Test
        @DisplayName("检索缺省 current=1 / size=20，types 以逗号串透传")
        void searchDefaults() throws Exception {
            mockMvc.perform(get("/asset/search?keyword=order")).andExpect(status().isOk());
            verify(assetCatalogService).search("order", null, 1, 20, null, null, null);
        }

        @Test
        @DisplayName("详情页签缺省 limit=20")
        void tabDefaults() throws Exception {
            mockMvc.perform(get("/asset/table/101/preview")).andExpect(status().isOk());
            verify(assetCatalogService).preview(101L, 20, null);
        }

        @Test
        @DisplayName("盘点不传 projectCode 时按全量口径（null 透传，由服务层决定收窄）")
        void inventoryDefaults() throws Exception {
            mockMvc.perform(get("/asset/inventory/overview")).andExpect(status().isOk());
            verify(assetInventoryService).overview(null, null, null, null);
        }

        @Test
        @DisplayName("盘点：domainCode（数仓名称）/ layerCode（分层）逐字透传给服务层")
        void inventoryCategoryBinding() throws Exception {
            mockMvc.perform(get("/asset/inventory/overview?domainCode=order&layerCode=dwd"))
                    .andExpect(status().isOk());
            verify(assetInventoryService).overview(null, null, "order", "dwd");
        }

        @Test
        @DisplayName("env 参数：目录树 / 检索 / 建议 / 盘点 / 看板逐一透传给服务层")
        void envBinding() throws Exception {
            mockMvc.perform(get("/asset/catalog/tree?env=prod")).andExpect(status().isOk());
            verify(assetCatalogService).tree("engine", null, null, null, "prod");

            mockMvc.perform(get("/asset/search?keyword=order&env=gray")).andExpect(status().isOk());
            verify(assetCatalogService).search("order", null, 1, 20, null, null, "gray");

            mockMvc.perform(get("/asset/suggest?keyword=ord&env=test")).andExpect(status().isOk());
            verify(assetCatalogService).suggest("ord", "test");

            mockMvc.perform(get("/asset/inventory/overview?env=prod")).andExpect(status().isOk());
            verify(assetInventoryService).overview(null, "prod", null, null);

            mockMvc.perform(get("/asset/inventory/health?env=gray")).andExpect(status().isOk());
            verify(assetInventoryService).health(null, "gray", null, null);

            mockMvc.perform(get("/asset/inventory/top-assets?env=test")).andExpect(status().isOk());
            verify(assetInventoryService).topAssets(100, "test");

            mockMvc.perform(get("/asset/inventory/size-growth?env=prod")).andExpect(status().isOk());
            // 阈值与对比窗口默认值由服务层兜底，控制器只透传显式参数
            verify(assetInventoryService).sizeGrowth(null, null, null, "prod");

            mockMvc.perform(get("/asset/inventory/top-compute?env=gray")).andExpect(status().isOk());
            verify(schedulerTaskMetricsService).topCompute("resource", 50, "gray");

            mockMvc.perform(get("/asset/inventory/task-delay?env=test")).andExpect(status().isOk());
            verify(schedulerTaskMetricsService).taskDelay(30, "test");
        }

        @Test
        @DisplayName("env 未传时按 null 透传（服务层据此判定「不按环境过滤」）")
        void envOmittedIsNull() throws Exception {
            when(assetInventoryService.topAssets(5, null)).thenReturn(List.of());
            mockMvc.perform(get("/asset/inventory/top-assets?limit=5")).andExpect(status().isOk());
            verify(assetInventoryService).topAssets(5, null);

            when(schedulerTaskMetricsService.taskDelay(7, null)).thenReturn(new AssetTaskDelayVO());
            mockMvc.perform(get("/asset/inventory/task-delay?days=7")).andExpect(status().isOk());
            verify(schedulerTaskMetricsService).taskDelay(7, null);
        }

        @Test
        @DisplayName("治理写接口：请求体字段名按 camelCase 绑定到 DTO")
        void writeBodyBinding() throws Exception {
            mockMvc.perform(put("/asset/table/101/owner")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"businessOwner\":\"zhangsan\",\"techOwner\":\"lisi\"}"))
                    .andExpect(status().isOk());
            org.mockito.ArgumentCaptor<com.bigdata.modules.asset.dto.AssetOwnerUpdateDTO> owner =
                    org.mockito.ArgumentCaptor.forClass(com.bigdata.modules.asset.dto.AssetOwnerUpdateDTO.class);
            verify(assetGovernanceService).updateOwner(org.mockito.ArgumentMatchers.eq(101L), owner.capture());
            assertEquals("zhangsan", owner.getValue().getBusinessOwner());
            assertEquals("lisi", owner.getValue().getTechOwner());

            mockMvc.perform(put("/asset/table/101/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tagCodes\":[\"core\",\"pii\"]}"))
                    .andExpect(status().isOk());
            org.mockito.ArgumentCaptor<com.bigdata.modules.asset.dto.AssetTagsUpdateDTO> tags =
                    org.mockito.ArgumentCaptor.forClass(com.bigdata.modules.asset.dto.AssetTagsUpdateDTO.class);
            verify(assetGovernanceService).updateTags(org.mockito.ArgumentMatchers.eq(101L), tags.capture());
            assertEquals(List.of("core", "pii"), tags.getValue().getTagCodes());
        }
    }

    // ==================================================================
    // 样例数据
    // ==================================================================

    private static CatalogTreeNodeVO treeRoot() {
        CatalogTreeNodeVO table = new CatalogTreeNodeVO();
        table.setKey("table:101");
        table.setLabel("dwd_order_detail_di");
        table.setNodeKind("table");
        table.setTableMetadataId(101L);
        table.setTableName("dwd_order_detail_di");
        table.setStorageEngine("hive");
        table.setLayerCode("dwd");
        table.setDomainCode("order");
        table.setUnclassified(true);

        CatalogTreeNodeVO database = new CatalogTreeNodeVO();
        database.setKey("db:1:dw");
        database.setLabel("dw");
        database.setNodeKind("database");
        database.setDatasourceId(1L);
        database.setDatabaseName("dw");
        database.setChildren(List.of(table));

        CatalogTreeNodeVO datasource = new CatalogTreeNodeVO();
        datasource.setKey("engine:1");
        datasource.setLabel("hive-prod");
        datasource.setNodeKind("datasource");
        datasource.setDatasourceId(1L);
        datasource.setAssetCount(1);
        datasource.setChildren(List.of(database));
        return datasource;
    }

    private static AssetTableDetailVO detail() {
        AssetColumnVO column = new AssetColumnVO();
        column.setId(1001L);
        column.setColumnName("order_id");
        column.setColumnType("bigint");
        column.setColumnComment("订单号");
        column.setIsPartition(false);
        column.setIsPrimaryKey(true);
        column.setNullable(false);
        column.setOrdinalPosition(1);
        column.setSecurityLevel("L2");

        AssetTagVO tag = new AssetTagVO();
        tag.setId(1L);
        tag.setTagCode("core");
        tag.setTagName("核心资产");
        tag.setColor("#d93025");

        AssetTableDetailVO vo = new AssetTableDetailVO();
        vo.setId(101L);
        vo.setDatasourceId(1L);
        vo.setDatasourceName("hive-prod");
        vo.setStorageEngine("hive");
        vo.setDatabaseName("dw");
        vo.setTableName("dwd_order_detail_di");
        vo.setTableType("MANAGED_TABLE");
        vo.setTableComment("订单明细");
        vo.setBusinessOwner("zhangsan");
        vo.setTechOwner("lisi");
        vo.setTopic("交易");
        vo.setVisibility("internal");
        vo.setCategoryCode("trade.order");
        vo.setLayerCode("dwd");
        vo.setDomainCode("order");
        vo.setConventionParsed(true);
        vo.setRefreshCycle("di");
        vo.setAssetLevel("L1");
        vo.setRowCount(1200000L);
        vo.setPartitionColumns(List.of("dt"));
        vo.setHealthIssueCount(2);
        vo.setColumns(List.of(column));
        vo.setTags(List.of(tag));
        return vo;
    }

    private static AssetSearchHitVO hit(String type, Long tableId) {
        AssetSearchHitVO hit = new AssetSearchHitVO();
        hit.setAssetType(type);
        hit.setId(tableId);
        hit.setTableMetadataId(tableId);
        hit.setTitle("dwd_order_detail_di");
        hit.setSubtitle("dw.dwd_order_detail_di");
        hit.setMatchedText("dwd_order_detail_di");
        hit.setOwnerName("zhangsan");
        hit.setLayerCode("dwd");
        hit.setDomainCode("order");
        return hit;
    }

    private static AssetSearchResultVO searchResult() {
        AssetSearchHitVO column = hit("column", 101L);
        column.setLayerCode("core");
        AssetSearchHitVO indicator = hit("indicator", 501L);
        indicator.setTitle("IND_ORDER_AMT");

        AssetSearchResultVO result = new AssetSearchResultVO();
        result.setTables(List.of(hit("table", 101L)));
        result.setColumns(List.of(column));
        result.setIndicators(List.of(indicator));
        result.setTotal(3);
        return result;
    }

    private static AssetQualitySummaryVO quality() {
        AssetQualitySummaryVO vo = new AssetQualitySummaryVO();
        vo.setRuleCount(6);
        vo.setEnabledRuleCount(5);
        vo.setLastRunStatus("FAILED");
        vo.setLastRunTime("2026-09-17T02:10:00");
        vo.setErrorCount(1);
        vo.setFailCount(0);
        vo.setProblemRuleCount(1);
        return vo;
    }

    private static AssetPreviewVO preview() {
        AssetPreviewVO vo = new AssetPreviewVO();
        vo.setColumns(List.of("order_id", "amt"));
        vo.setRows(List.of(List.of("1001", "88.50"), List.of("1002", "12.00")));
        return vo;
    }

    private static AssetProducerVO producer() {
        AssetProducerVO vo = new AssetProducerVO();
        vo.setProjectCode("proj_a");
        vo.setWorkflowCode(10001L);
        vo.setWorkflowName("dwd_order_di");
        vo.setNodeName("dwd_order_detail_di");
        vo.setOwnerName("zhangsan");
        vo.setScheduleCycle("0 0 2 * * ?");
        vo.setLastRunState("SUCCESS");
        vo.setLastRunTime("2026-09-17T02:03:11");
        return vo;
    }

    private static AssetIndicatorVO indicator() {
        AssetIndicatorVO vo = new AssetIndicatorVO();
        vo.setId(501L);
        vo.setCode("IND_ORDER_AMT");
        vo.setName("订单金额");
        vo.setTopic("交易");
        vo.setBusinessOwner("zhangsan");
        vo.setFieldName("order_id");
        return vo;
    }

    private static AssetInventoryVO inventory() {
        AssetInventoryVO.LayerStat layer = new AssetInventoryVO.LayerStat();
        layer.setLayerCode("dwd");
        layer.setTableCount(40);
        layer.setSizeBytes(1000000000L);

        AssetInventoryVO.DomainStat domain = new AssetInventoryVO.DomainStat();
        domain.setDomainCode("order");
        domain.setTableCount(15);
        domain.setSizeBytes(400000000L);

        AssetInventoryVO.DatasourceStat datasource = new AssetInventoryVO.DatasourceStat();
        datasource.setDatasourceId(1L);
        datasource.setDatasourceName("hive-prod");
        datasource.setTableCount(120);

        AssetInventoryVO vo = new AssetInventoryVO();
        vo.setTotalTables(120);
        vo.setTotalSizeBytes(5000000000L);
        vo.setTotalColumns(2400);
        vo.setUnclassifiedCount(7);
        vo.setByLayer(List.of(layer));
        vo.setByDomain(List.of(domain));
        vo.setByDatasource(List.of(datasource));
        return vo;
    }

    private static AssetHealthIssueVO healthIssue() {
        AssetHealthIssueVO.Sample sample = new AssetHealthIssueVO.Sample();
        sample.setTableMetadataId(101L);
        sample.setDatabaseName("dw");
        sample.setTableName("dwd_order_detail_di");

        AssetHealthIssueVO vo = new AssetHealthIssueVO();
        vo.setIssueCode("NO_TABLE_COMMENT");
        vo.setIssueName("缺少表说明");
        vo.setCount(12);
        vo.setImpact("影响人和智能体理解表用途");
        vo.setSamples(List.of(sample));
        return vo;
    }

    private static AssetCategoryVO category() {
        AssetCategoryVO vo = new AssetCategoryVO();
        vo.setCode("trade.order");
        vo.setName("交易-订单");
        vo.setParentCode("trade");
        vo.setCategoryKind("domain");
        return vo;
    }

    private static AssetDocVO doc() {
        AssetDocVO vo = new AssetDocVO();
        vo.setContent("# 表说明");
        vo.setUpdateTime("2026-09-17T10:00:00");
        vo.setUpdateBy("zhangsan");
        return vo;
    }

    private static AssetDocHistoryVO docHistory() {
        AssetDocHistoryVO vo = new AssetDocHistoryVO();
        vo.setVersion(2);
        vo.setUpdateTime("2026-09-17T09:00:00");
        vo.setUpdateBy("zhangsan");
        return vo;
    }

    @Nested
    @DisplayName("POST /asset/task/*/run - 任务手动触发")
    class TaskRunEndpointTests {

        @Test
        @DisplayName("快照：statDate 参数绑定并返回 camelCase 摘要")
        void testRunCatalogSnapshot() throws Exception {
            LocalDate statDate = LocalDate.of(2026, 9, 1);
            when(catalogSnapshotTask.runOnce(statDate))
                    .thenReturn(new AssetCatalogSnapshotTask.RunResult(statDate, 128, 3));

            JsonNode data = dataOf(post("/asset/task/catalog-snapshot/run").param("statDate", "2026-09-01"));

            assertEquals("2026-09-01", data.get("statDate").asText());
            assertEquals(128, data.get("tableCount").asInt());
            assertEquals(3, data.get("purged").asInt());
        }

        @Test
        @DisplayName("快照：缺省统计日按 T+1 口径取昨天")
        void testRunCatalogSnapshot_defaultYesterday() throws Exception {
            when(catalogSnapshotTask.runOnce(any(LocalDate.class)))
                    .thenReturn(new AssetCatalogSnapshotTask.RunResult(LocalDate.now().minusDays(1), 1, 0));

            dataOf(post("/asset/task/catalog-snapshot/run"));

            verify(catalogSnapshotTask).runOnce(LocalDate.now().minusDays(1));
        }

        @Test
        @DisplayName("未来统计日被拒绝：返回错误且不触发任务")
        void testRunFutureDateRejected() throws Exception {
            JsonNode body = call(post("/asset/task/catalog-snapshot/run")
                    .param("statDate", LocalDate.now().plusDays(1).toString()));

            assertNotEquals(200, body.get("code").asInt());
            verify(catalogSnapshotTask, never()).runOnce(any(LocalDate.class));
        }

        @Test
        @DisplayName("调度指标：返回采集摘要，skippedReason 原样透传")
        void testRunSchedulerMetrics() throws Exception {
            when(schedulerMetricsTask.runOnce(any(LocalDate.class)))
                    .thenReturn(new SchedulerTaskMetricsService.CollectResult(
                            LocalDate.of(2026, 9, 29), 0, 0, 0, "调度系统未配置"));

            JsonNode data = dataOf(post("/asset/task/scheduler-metrics/run"));

            assertEquals("调度系统未配置", data.get("skippedReason").asText());
            assertEquals(0, data.get("rowCount").asInt());
        }
    }
}
