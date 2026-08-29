package com.bigdata.modules.metadata.controller;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.test.ContractMockMvcSupport;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * metadata 侧因「StorageEngineType 并入 DatasourceType」而发生返回体变化的接口契约回归。
 * <p>
 * 最关键的差异：<b>{@code storage_engine} 字段的取值从枚举名变成了小写 code</b>。
 * 合并前 {@code StorageEngineType} 既无 {@code @JsonValue} 也无 {@code @EnumValue}，
 * Jackson 按枚举名输出（{@code "HIVE"}）；合并后字段类型换成带 {@code @JsonValue} 的
 * {@link DatasourceType}，输出变为 {@code "hive"}。前端若按大写枚举名做判断会全部失效。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("metadata 接口返回体契约回归")
class MetadataApiContractTest {

    private final ObjectMapper mapper = ContractMockMvcSupport.objectMapper();

    @Mock
    private TableMetadataService tableMetadataService;
    @Mock
    private DataAccessPermissionService dataAccessPermissionService;
    @Mock
    private DatasourceConfigMapper datasourceConfigMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MetadataController controller = new MetadataController();
        ReflectionTestUtils.setField(controller, "tableMetadataService", tableMetadataService);
        ReflectionTestUtils.setField(controller, "dataAccessPermissionService", dataAccessPermissionService);
        ReflectionTestUtils.setField(controller, "datasourceConfigMapper", datasourceConfigMapper);
        mockMvc = ContractMockMvcSupport.mockMvc(controller);
    }

    private ColumnMetadata column(DatasourceType engine, String db, String table, String colName, int ordinal) {
        ColumnMetadata col = new ColumnMetadata();
        col.setId((long) ordinal);
        col.setStorageEngine(engine);
        col.setDatabaseName(db);
        col.setTableName(table);
        col.setColumnName(colName);
        col.setColumnType("string");
        col.setOrdinalPosition(ordinal);
        col.setNullable(1);
        col.setSyncTime(new Date());
        return col;
    }

    private JsonNode performGet(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("GET /metadata/table/columns - storage_engine 字段取值")
    class StorageEngineFieldValueTests {

        @Test
        @DisplayName("storage_engine 输出小写 code（改动前为大写枚举名 HIVE）")
        void storageEngineSerializesAsCode() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.HIVE), eq("dw"), eq("orders")))
                    .thenReturn(List.of(column(DatasourceType.HIVE, "dw", "orders", "id", 1)));

            JsonNode data = performGet("/metadata/table/columns?storageEngine=hive&databaseName=dw&tableName=orders")
                    .get("data");

            assertEquals(1, data.size());
            assertEquals("hive", data.get(0).get("storage_engine").asText(),
                    "storage_engine 由大写枚举名 'HIVE' 变为小写 code 'hive'");
        }

        @Test
        @DisplayName("postgresql / clickhouse / starrocks 等长名同样输出 code")
        void longNamesSerializeAsCode() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.POSTGRESQL), eq("db"), eq("t")))
                    .thenReturn(List.of(column(DatasourceType.POSTGRESQL, "db", "t", "id", 1)));

            JsonNode data = performGet("/metadata/table/columns?storageEngine=postgresql&databaseName=db&tableName=t")
                    .get("data");

            assertEquals("postgresql", data.get(0).get("storage_engine").asText());
        }

        @Test
        @DisplayName("字段结构保持稳定：storage_engine / database_name / table_name / column_name")
        void fieldLayout() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.MYSQL), eq("db"), eq("t")))
                    .thenReturn(List.of(column(DatasourceType.MYSQL, "db", "t", "user_name", 2)));

            JsonNode col = performGet("/metadata/table/columns?storageEngine=mysql&databaseName=db&tableName=t")
                    .get("data").get(0);

            assertEquals("mysql", col.get("storage_engine").asText());
            assertEquals("db", col.get("database_name").asText());
            assertEquals("t", col.get("table_name").asText());
            assertEquals("user_name", col.get("column_name").asText());
            assertEquals(2, col.get("ordinal_position").asInt());
        }
    }

    @Nested
    @DisplayName("GET /metadata/table/columns - storageEngine 入参绑定")
    class StorageEngineParamBindingTests {

        @Test
        @DisplayName("小写 code 与大写枚举名都能正确绑定（改动前仅接受大写枚举名）")
        void acceptsBothCodeAndEnumName() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.HIVE), any(), any()))
                    .thenReturn(List.of(column(DatasourceType.HIVE, "dw", "orders", "id", 1)));

            JsonNode byCode = performGet("/metadata/table/columns?storageEngine=hive&databaseName=dw&tableName=orders")
                    .get("data");
            JsonNode byName = performGet("/metadata/table/columns?storageEngine=HIVE&databaseName=dw&tableName=orders")
                    .get("data");

            assertEquals(1, byCode.size());
            assertEquals(1, byName.size());
            verify(tableMetadataService, org.mockito.Mockito.times(2))
                    .getColumnsByNames(eq(DatasourceType.HIVE), any(), any());
        }

        @Test
        @DisplayName("未知 storageEngine 取值必须报 400，不能静默退化成“不过滤引擎”")
        void unknownValueMustNotSilentlyWidenQuery() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            // 模拟不同引擎下有同名表的场景：hive 一份、mysql 一份
            when(tableMetadataService.getColumnsByNames(isNull(), eq("dw"), eq("orders")))
                    .thenReturn(List.of(column(DatasourceType.MYSQL, "dw", "orders", "id", 1)));

            MvcResult result = mockMvc.perform(
                    get("/metadata/table/columns?storageEngine=not_a_type&databaseName=dw&tableName=orders")).andReturn();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());

            assertEquals(400, body.get("code").asInt(),
                    "非法 storageEngine 应被拒绝；若返回 200，查询条件里的引擎过滤会被丢弃，"
                            + "导致跨引擎同名表的列被一起返回");
            org.mockito.Mockito.verifyNoInteractions(tableMetadataService);
        }

        @Test
        @DisplayName("引擎过滤生效：hive 的查询不会返回 mysql 的同名列")
        void engineFilterIsApplied() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.HIVE), eq("dw"), eq("orders")))
                    .thenReturn(List.of(column(DatasourceType.HIVE, "dw", "orders", "id", 1)));
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.MYSQL), eq("dw"), eq("orders")))
                    .thenReturn(List.of(column(DatasourceType.MYSQL, "dw", "orders", "id", 1)));

            JsonNode hiveResult = performGet("/metadata/table/columns?storageEngine=hive&databaseName=dw&tableName=orders")
                    .get("data");
            JsonNode mysqlResult = performGet("/metadata/table/columns?storageEngine=mysql&databaseName=dw&tableName=orders")
                    .get("data");

            assertEquals("hive", hiveResult.get(0).get("storage_engine").asText());
            assertEquals("mysql", mysqlResult.get(0).get("storage_engine").asText());
        }

        @Test
        @DisplayName("不传 storageEngine 时按库+表查询（保持改动前行为）")
        void missingParamQueriesByDbAndTable() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(isNull(), eq("dw"), eq("orders")))
                    .thenReturn(List.of(column(DatasourceType.HIVE, "dw", "orders", "id", 1)));

            MvcResult result = mockMvc.perform(
                    get("/metadata/table/columns?databaseName=dw&tableName=orders"))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
            assertEquals(1, data.size());
            verify(tableMetadataService).getColumnsByNames(isNull(), eq("dw"), eq("orders"));
        }
    }

    @Nested
    @DisplayName("GET /metadata/tables - 表列表返回体")
    class TableListTests {

        @Test
        @DisplayName("TableMetadataVO.storage_engine 同样输出小写 code")
        void tableStorageEngineSerializesAsCode() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);

            TableMetadata table = new TableMetadata();
            table.setId(1L);
            table.setDatasourceId(1L);
            table.setStorageEngine(DatasourceType.HIVE);
            table.setDatabaseName("dw");
            table.setTableName("orders");
            table.setSyncTime(new Date());

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<TableMetadata> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
            page.setRecords(List.of(table));
            when(tableMetadataService.getTableList(1, 10000, 1L, null, null, null)).thenReturn(page);

            JsonNode record = performGet("/metadata/tables?datasourceId=1").get("data").get("records").get(0);

            assertNotNull(record);
            assertEquals("hive", record.get("storage_engine").asText());
            assertEquals("dw", record.get("database_name").asText());
        }
    }

    @Nested
    @DisplayName("空值与边界")
    class EdgeCaseTests {

        @Test
        @DisplayName("库表不存在时返回空列表，storage_engine 不出现在响应中")
        void emptyResult() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.HBASE), eq("nodb"), eq("notable")))
                    .thenReturn(List.of());

            JsonNode data = performGet("/metadata/table/columns?storageEngine=hbase&databaseName=nodb&tableName=notable")
                    .get("data");

            assertTrue(data.isEmpty());
        }

        @Test
        @DisplayName("storage_engine 为 null 的记录不输出该字段（NON_NULL 生效）")
        void nullStorageEngineOmitted() throws Exception {
            when(dataAccessPermissionService.isAdmin()).thenReturn(true);
            ColumnMetadata col = column(null, "dw", "orders", "id", 1);
            when(tableMetadataService.getColumnsByNames(eq(DatasourceType.HIVE), eq("dw"), eq("orders")))
                    .thenReturn(List.of(col));

            JsonNode node = performGet("/metadata/table/columns?storageEngine=hive&databaseName=dw&tableName=orders")
                    .get("data").get(0);

            assertNull(node.get("storage_engine"));
        }
    }
}
