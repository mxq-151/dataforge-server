package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.vo.DatasourceTypeMetaVO;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.CodeType;
import com.bigdata.modules.metadata.service.MetadataSyncService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * devops 侧因「合并 StorageEngineType 到 DatasourceType」而发生返回体变化的接口契约回归。
 * <ul>
 *   <li>{@code GET /devops/datasource/types}：由 code 字符串数组变为富结构对象，且类型数量 8 → 13；</li>
 *   <li>{@code GET /devops/datasource/list|all}：返回结果中不再包含 {@code storage_engine} 字段；</li>
 *   <li>{@code GET /devops/execution/engines}：返回结构由引擎枚举变为带 exec/store/language 的对象。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("devops 接口返回体契约回归")
class DatasourceApiContractTest {

    private final ObjectMapper mapper = ContractMockMvcSupport.objectMapper();

    @Mock
    private DatasourceConfigService datasourceConfigService;
    @Mock
    private MetadataSyncService metadataSyncService;
    @Mock
    private CodeExecutionService codeExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DatasourceConfigController datasourceController = new DatasourceConfigController();
        org.springframework.test.util.ReflectionTestUtils.setField(
                datasourceController, "datasourceConfigService", datasourceConfigService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                datasourceController, "metadataSyncService", metadataSyncService);

        ExecutionEngineController engineController = new ExecutionEngineController();
        org.springframework.test.util.ReflectionTestUtils.setField(
                engineController, "codeExecutionService", codeExecutionService);

        mockMvc = ContractMockMvcSupport.mockMvc(datasourceController, engineController);
    }

    private JsonNode getJson(String url, Object... uriVars) throws Exception {
        MvcResult result = mockMvc.perform(get(url, uriVars))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private DatasourceConfig config(Long id, String name, DatasourceType type) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName(name);
        config.setDatasourceType(type);
        config.setConnectionType(ConnectionType.JDBC);
        config.setUrl("jdbc:mysql://localhost:3306/" + name);
        config.setUsername("root");
        config.setConnectionStatus(1);
        config.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return config;
    }

    @Nested
    @DisplayName("GET /devops/datasource/types - 数据源类型列表")
    class TypesEndpointTests {

        @Test
        @DisplayName("返回 13 条，字段结构为 code/description/exec/store/language")
        void returnsRichTypeMeta() throws Exception {
            JsonNode data = getJson("/devops/datasource/types").get("data");

            assertEquals(13, data.size(), "合并后类型数量应为 13");
            for (JsonNode node : data) {
                assertTrue(node.has("code"), "缺少 code 字段");
                assertTrue(node.has("description"), "缺少 description 字段");
                assertTrue(node.has("exec"), "缺少 exec 字段");
                assertTrue(node.has("store"), "缺少 store 字段");
                assertTrue(node.has("language"), "缺少 language 字段");
            }
        }

        @Test
        @DisplayName("第一条为 spark，且描述使用 Apache 前缀长名")
        void firstEntryAndDescriptions() throws Exception {
            JsonNode data = getJson("/devops/datasource/types").get("data");

            assertEquals("spark", data.get(0).get("code").asText());
            assertEquals("Apache Spark", data.get(0).get("description").asText());

            JsonNode kyuubi = findByCode(data, "kyuubi");
            assertEquals("Apache Kyuubi", kyuubi.get("description").asText(),
                    "kyuubi 描述由 'Kyuubi' 变为 'Apache Kyuubi'");
            JsonNode doris = findByCode(data, "doris");
            assertEquals("Apache Doris", doris.get("description").asText(),
                    "doris 描述由 'Doris' 变为 'Apache Doris'");
        }

        @Test
        @DisplayName("spark 的 language 为 java/scala/python/sql，mysql 仅为 sql")
        void languagesPerType() throws Exception {
            JsonNode data = getJson("/devops/datasource/types").get("data");

            List<String> sparkLang = codes(findByCode(data, "spark").get("language"));
            assertEquals(List.of("java", "scala", "python", "sql"), sparkLang);

            List<String> mysqlLang = codes(findByCode(data, "mysql").get("language"));
            assertEquals(List.of("sql"), mysqlLang);
        }

        @Test
        @DisplayName("spark exec=true/store=false，hive exec=false/store=true")
        void execStoreFlags() throws Exception {
            JsonNode data = getJson("/devops/datasource/types").get("data");

            assertTrue(findByCode(data, "spark").get("exec").asBoolean());
            assertFalse(findByCode(data, "spark").get("store").asBoolean());
            assertFalse(findByCode(data, "hive").get("exec").asBoolean());
            assertTrue(findByCode(data, "hive").get("store").asBoolean());
        }

        private JsonNode findByCode(JsonNode array, String code) {
            for (JsonNode node : array) {
                if (code.equals(node.get("code").asText())) {
                    return node;
                }
            }
            throw new AssertionError("类型列表中找不到 code=" + code);
        }

        private List<String> codes(JsonNode array) {
            List<String> out = new java.util.ArrayList<>();
            array.forEach(n -> out.add(n.asText()));
            return out;
        }
    }

    @Nested
    @DisplayName("GET /devops/datasource/list - 数据源列表返回体")
    class ListEndpointTests {

        @Test
        @DisplayName("datasource_type 序列化为小写 code")
        void datasourceTypeIsLowercaseCode() throws Exception {
            when(datasourceConfigService.getDatasourceList(anyInt(), anyInt(), isNull(), isNull()))
                    .thenReturn(pageOf(config(1L, "ds_mysql", DatasourceType.MYSQL)));

            JsonNode record = getJson("/devops/datasource/list").get("data")
                    .get("records").get(0);

            assertEquals("mysql", record.get("datasource_type").asText());
        }

        @Test
        @DisplayName("响应中不再包含 storage_engine 字段（合并后该字段已从 VO 移除）")
        void storageEngineFieldRemoved() throws Exception {
            when(datasourceConfigService.getDatasourceList(anyInt(), anyInt(), isNull(), isNull()))
                    .thenReturn(pageOf(config(1L, "ds_mysql", DatasourceType.MYSQL)));

            JsonNode record = getJson("/devops/datasource/list").get("data")
                    .get("records").get(0);

            assertFalse(record.has("storage_engine"),
                    "合并后 DatasourceConfigVO 已移除 storageEngine 字段，若前端仍依赖该字段需同步改造");
        }

        @Test
        @DisplayName("datasourceType 过滤参数支持大写枚举名与小写 code 两种写法")
        void filterParamBinding() throws Exception {
            when(datasourceConfigService.getDatasourceList(anyInt(), anyInt(), isNull(), eq(DatasourceType.HIVE)))
                    .thenReturn(pageOf(config(1L, "ds_hive", DatasourceType.HIVE)));

            JsonNode byLower = getJson("/devops/datasource/list?datasourceType=hive").get("data").get("records");
            JsonNode byUpper = getJson("/devops/datasource/list?datasourceType=HIVE").get("data").get("records");

            assertEquals(1, byLower.size());
            assertEquals(1, byUpper.size());
            verify(datasourceConfigService, atLeastOnce())
                    .getDatasourceList(anyInt(), anyInt(), isNull(), eq(DatasourceType.HIVE));
        }

        @Test
        @DisplayName("datasourceType 传非法值时报 400，不静默返回全量列表")
        void illegalFilterParamReturnsBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/devops/datasource/list?datasourceType=nope")).andReturn();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());

            assertEquals(400, body.get("code").asInt(),
                    "非法 datasourceType 应被拒绝，否则过滤条件被丢弃会返回全量数据源");
            org.mockito.Mockito.verifyNoInteractions(datasourceConfigService);
        }

        private Page<DatasourceConfig> pageOf(DatasourceConfig... configs) {
            Page<DatasourceConfig> page = new Page<>(1, 10, configs.length);
            page.setRecords(List.of(configs));
            return page;
        }
    }

    @Nested
    @DisplayName("POST /devops/datasource - 创建数据源请求体")
    class SaveEndpointTests {

        /**
         * 注意：全局 Jackson 配置了 SNAKE_CASE，请求体字段名为 datasource_name / datasource_type 等。
         */
        @Test
        @DisplayName("请求体中的 storage_engine 字段已被移除，不再参与校验")
        void storageEngineNoLongerRequired() throws Exception {
            String body = """
                    {
                      "datasource_name": "ds_x",
                      "datasource_type": "mysql",
                      "connection_type": "jdbc",
                      "url": "jdbc:mysql://localhost:3306/x",
                      "username": "root",
                      "password": "123456"
                    }
                    """;

            mockMvc.perform(post("/devops/datasource")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());

            verify(datasourceConfigService).saveDatasource(any(DatasourceConfig.class));
        }

        @Test
        @DisplayName("DTO 上不再存在 storageEngine 字段，历史请求体携带的 storage_engine 被静默忽略")
        void dtoFieldRemoved() throws Exception {
            boolean exists = java.util.Arrays.stream(DatasourceConfigDTO.class.getDeclaredFields())
                    .anyMatch(f -> "storageEngine".equals(f.getName()));
            assertFalse(exists, "DatasourceConfigDTO 已移除 storageEngine 字段");

            String legacyBody = """
                    {
                      "datasource_name": "ds_y",
                      "datasource_type": "mysql",
                      "connection_type": "jdbc",
                      "storage_engine": "MYSQL",
                      "url": "jdbc:mysql://localhost:3306/y",
                      "username": "root",
                      "password": "123456"
                    }
                    """;

            mockMvc.perform(post("/devops/datasource")
                            .contentType("application/json")
                            .content(legacyBody))
                    .andExpect(status().isOk());

            verify(datasourceConfigService).saveDatasource(any(DatasourceConfig.class));
        }

        @Test
        @DisplayName("改动前 storageEngine 是必填项，现在缺失也能创建成功（契约放宽）")
        void storageEngineWasMandatoryBeforeMerge() throws Exception {
            boolean hasNotNull = java.util.Arrays.stream(DatasourceConfigDTO.class.getDeclaredFields())
                    .noneMatch(f -> "storageEngine".equals(f.getName()));
            assertTrue(hasNotNull, "合并后 DTO 不再声明 storageEngine 的 @NotNull 校验");
        }
    }

    @Nested
    @DisplayName("GET /devops/execution/engines - 执行引擎列表")
    class EngineEndpointTests {

        @Test
        @DisplayName("引擎条目携带 exec/store/language 元数据")
        void enginesCarryMetadata() throws Exception {
            when(codeExecutionService.getAvailableEngines())
                    .thenReturn(List.of(DatasourceType.SPARK, DatasourceType.DORIS, DatasourceType.KYUUBI));

            JsonNode data = getJson("/devops/execution/engines").get("data");

            assertEquals(3, data.size());
            List<String> codes = new java.util.ArrayList<>();
            data.forEach(n -> codes.add(n.get("code").asText()));
            assertEquals(List.of("spark", "doris", "kyuubi"), codes, "返回顺序应与服务层一致");

            assertTrue(data.get(0).get("exec").asBoolean());
            assertFalse(data.get(0).get("store").asBoolean());
            assertEquals("Apache Spark", data.get(0).get("description").asText());
        }

        @Test
        @DisplayName("/engines/supported 依据 language 判定")
        void supportedEndpoint() throws Exception {
            when(codeExecutionService.isExecutionSupported("spark", "scala")).thenReturn(true);
            when(codeExecutionService.isExecutionSupported("doris", "scala")).thenReturn(false);

            assertTrue(getJson("/devops/execution/engines/supported?engineCode=spark&codeType=scala")
                    .get("data").asBoolean());
            assertFalse(getJson("/devops/execution/engines/supported?engineCode=doris&codeType=scala")
                    .get("data").asBoolean());
        }

        @Test
        @DisplayName("DatasourceTypeMetaVO.from 对每种类型都能产出完整元数据")
        void metaVoCompleteness() {
            for (DatasourceType type : DatasourceType.values()) {
                DatasourceTypeMetaVO vo = DatasourceTypeMetaVO.from(type);
                assertEquals(type.getCode(), vo.getCode());
                assertEquals(type.getDescription(), vo.getDescription());
                assertEquals(type.isExec(), vo.isExec());
                assertEquals(type.isStore(), vo.isStore());
                assertEquals(type.getLanguage().stream().map(CodeType::getCode).collect(Collectors.toList()),
                        vo.getLanguage());
            }
        }
    }
}
