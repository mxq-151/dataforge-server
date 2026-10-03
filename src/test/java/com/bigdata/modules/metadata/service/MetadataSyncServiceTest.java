package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.development.service.execution.ExecutionStrategy;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
import com.bigdata.modules.development.service.execution.AbstractBaseExecutionStrategy;
import com.bigdata.common.sql.CalciteDdlParser;
import com.bigdata.modules.development.service.execution.CodeExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataSyncServiceTest {

    @Mock
    private TableMetadataService tableMetadataService;

    @Mock
    private DatasourceConfigService datasourceConfigService;

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private ExecutionStrategyFactory executionStrategyFactory;

    @Mock
    private ExecutionStrategy executionStrategy;

    @InjectMocks
    private MetadataSyncService metadataSyncService;

    private DatasourceConfig mockConfig;

    @BeforeEach
    void setUp() {
        mockConfig = new DatasourceConfig();
        mockConfig.setId(1L);
        mockConfig.setDatasourceType(DatasourceType.HIVE);
        mockConfig.setDatasourceName("test-kyuubi");
    }

    // ==================== syncSingleTableMetadata 测试 ====================

    @Test
    void testSyncSingleTableMetadata_DROP() {
        boolean result = metadataSyncService.syncSingleTableMetadata(1L, DatasourceType.HIVE, "ods", "user_info", "DROP", null);
        assertTrue(result);
        verify(tableMetadataService).deleteTableByNames(DatasourceType.HIVE, "ods", "user_info");
    }

    @Test
    void testSyncSingleTableMetadata_success() throws Exception {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);

        TableMetadata existingTable = new TableMetadata();
        existingTable.setId(100L);
        existingTable.setTableName("user_info");
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, "ods", "user_info")).thenReturn(existingTable);

        ColumnMetadataVO colVo = new ColumnMetadataVO();
        colVo.setColumnName("id");
        colVo.setColumnType("BIGINT");
        colVo.setOrdinalPosition(1);
        when(executionStrategy.listColumns(eq(mockConfig), eq("ods"), eq("user_info")))
                .thenReturn(Collections.singletonList(colVo));

        boolean result = metadataSyncService.syncSingleTableMetadata(1L, DatasourceType.HIVE, "ods", "user_info", "CREATE", null);
        assertTrue(result);
        verify(tableMetadataService).syncSingleTable(eq(DatasourceType.HIVE), eq(existingTable), anyList());
    }

    @Test
    void testSyncSingleTableMetadata_configNotFound() {
        when(datasourceConfigService.getDatasourceById(999L)).thenReturn(null);
        boolean result = metadataSyncService.syncSingleTableMetadata(999L, DatasourceType.HIVE, "ods", "t", "CREATE", null);
        assertFalse(result);
    }

    // ==================== syncAllMetadataByDatasourceId 测试 ====================

    @Test
    void testSyncAllMetadataByDatasourceId_configNotFound() {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(null);
        boolean result = metadataSyncService.syncAllMetadataByDatasourceId(1L);
        assertFalse(result);
    }

    @Test
    void testSyncAllMetadataByDatasourceId_noDatabases() throws Exception {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listDatabases(mockConfig)).thenReturn(Collections.emptyList());

        boolean result = metadataSyncService.syncAllMetadataByDatasourceId(1L);
        assertFalse(result);
    }

    @Test
    void testSyncAllMetadataByDatasourceId_success() throws Exception {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);

        // 返回2个数据库，过滤掉系统库
        when(executionStrategy.listDatabases(mockConfig))
                .thenReturn(Arrays.asList("default", "information_schema", "tmall_dw"));

        TableMetadata table1 = new TableMetadata();
        table1.setTableName("order_detail");
        table1.setDatabaseName("tmall_dw");

        when(executionStrategy.listTables(eq(mockConfig), eq("tmall_dw")))
                .thenReturn(Collections.singletonList(table1));
        when(executionStrategy.listTables(eq(mockConfig), eq("default")))
                .thenReturn(Collections.emptyList());

        when(tableMetadataMapper.getTableMetadata(any(), anyString(), anyString())).thenReturn(null);

        ColumnMetadataVO colVo = new ColumnMetadataVO();
        colVo.setColumnName("id");
        colVo.setOrdinalPosition(1);
        when(executionStrategy.listColumns(eq(mockConfig), eq("tmall_dw"), eq("order_detail")))
                .thenReturn(Collections.singletonList(colVo));

        boolean result = metadataSyncService.syncAllMetadataByDatasourceId(1L);
        assertTrue(result);
        verify(tableMetadataService, atLeastOnce()).syncSingleTable(any(), any(), anyList());
    }

    // ==================== 辅助方法测试 ====================

    @Test
    void testIsSystemDatabase() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("isSystemDatabase", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(metadataSyncService, (String) null));
        assertTrue((Boolean) method.invoke(metadataSyncService, ""));
        assertTrue((Boolean) method.invoke(metadataSyncService, "information_schema"));
        assertTrue((Boolean) method.invoke(metadataSyncService, "mysql"));
        assertTrue((Boolean) method.invoke(metadataSyncService, "performance_schema"));
        assertTrue((Boolean) method.invoke(metadataSyncService, "sys"));
        assertFalse((Boolean) method.invoke(metadataSyncService, "default"));
        assertFalse((Boolean) method.invoke(metadataSyncService, "tmall_dw"));
    }

    @Test
    void testMergeTableMetadata() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("mergeTableMetadata",
                TableMetadata.class, TableMetadata.class);
        method.setAccessible(true);

        TableMetadata target = new TableMetadata();
        target.setTableName("old_table");
        target.setTableComment("old_comment");

        TableMetadata source = new TableMetadata();
        source.setTableName("new_table");
        source.setTableComment("new_comment");
        source.setTableType("MANAGED_TABLE");
        source.setCatalogName("hive");
        source.setStorageFormat("ORC");
        source.setCompression("SNAPPY");
        source.setLocation("hdfs://tmp");
        source.setRowCount(1000L);
        source.setSizeBytes(2048L);
        source.setTableCreateTime(new Date());

        method.invoke(metadataSyncService, target, source);

        assertEquals("new_comment", target.getTableComment());
        assertEquals("MANAGED_TABLE", target.getTableType());
        assertEquals("hive", target.getCatalogName());
        assertEquals("ORC", target.getStorageFormat());
        assertEquals("SNAPPY", target.getCompression());
        assertEquals("hdfs://tmp", target.getLocation());
        assertEquals(1000L, target.getRowCount());
        assertEquals(2048L, target.getSizeBytes());
        assertNotNull(target.getTableCreateTime());
        // tableName 不应被覆盖（merge方法中无此字段）
        assertEquals("old_table", target.getTableName());
    }

    @Test
    void testMergeTableMetadata_nullSourceFields() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("mergeTableMetadata",
                TableMetadata.class, TableMetadata.class);
        method.setAccessible(true);

        TableMetadata target = new TableMetadata();
        target.setTableComment("original");
        target.setRowCount(500L);

        TableMetadata source = new TableMetadata();
        // 所有字段为null，不应覆盖target

        method.invoke(metadataSyncService, target, source);

        assertEquals("original", target.getTableComment());
        assertEquals(500L, target.getRowCount());
    }

    @Test
    void testConvertToColumnMetadataEntities() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("convertToColumnMetadataEntities",
                List.class, TableMetadata.class, DatasourceType.class, String.class);
        method.setAccessible(true);

        TableMetadata table = new TableMetadata();
        table.setId(10L);
        table.setTableName("test_table");

        List<ColumnMetadataVO> vos = new ArrayList<>();
        ColumnMetadataVO vo1 = new ColumnMetadataVO();
        vo1.setColumnName("id");
        vo1.setColumnType("BIGINT");
        vo1.setColumnComment("主键");
        vo1.setOrdinalPosition(1);
        vo1.setNullable(0);
        vo1.setIsPrimaryKey(1);
        vo1.setTableName("test_table");
        vos.add(vo1);

        ColumnMetadataVO vo2 = new ColumnMetadataVO();
        vo2.setColumnName("name");
        vo2.setColumnType("STRING");
        vo2.setColumnComment("姓名");
        vo2.setOrdinalPosition(2);
        vo2.setNullable(1);
        vo2.setIsPrimaryKey(0);
        vo2.setTableName("test_table");
        vos.add(vo2);

        @SuppressWarnings("unchecked")
        List<ColumnMetadata> columns = (List<ColumnMetadata>) method.invoke(
                metadataSyncService, vos, table, DatasourceType.HIVE, "test_db");

        assertEquals(2, columns.size());

        ColumnMetadata col1 = columns.get(0);
        assertEquals(10L, col1.getTableMetadataId());
        assertEquals(DatasourceType.HIVE, col1.getStorageEngine());
        assertEquals("test_db", col1.getDatabaseName());
        assertEquals("test_table", col1.getTableName());
        assertEquals("id", col1.getColumnName());
        assertEquals("BIGINT", col1.getColumnType());
        assertEquals("主键", col1.getColumnComment());
        assertEquals(1, col1.getOrdinalPosition());
        assertEquals(0, col1.getNullable());
        assertEquals(1, col1.getIsPrimaryKey());
        assertNotNull(col1.getSyncTime());

        ColumnMetadata col2 = columns.get(1);
        assertEquals("name", col2.getColumnName());
        assertEquals(1, col2.getNullable());
        assertEquals(0, col2.getIsPrimaryKey());
    }

    @Test
    void testConvertToColumnMetadataEntities_nullInput() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("convertToColumnMetadataEntities",
                List.class, TableMetadata.class, DatasourceType.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ColumnMetadata> result1 = (List<ColumnMetadata>) method.invoke(
                metadataSyncService, null, null, DatasourceType.HIVE, "db");
        assertTrue(result1.isEmpty());

        @SuppressWarnings("unchecked")
        List<ColumnMetadata> result2 = (List<ColumnMetadata>) method.invoke(
                metadataSyncService, Collections.emptyList(), null, DatasourceType.HIVE, "db");
        assertTrue(result2.isEmpty());
    }

    // ==================== 分区列 / 压缩算法 / 默认值 完善测试 ====================

    /**
     * DDL 路径：Hive 表属性中的分区列（含类型声明）与压缩算法应规范化后落库，
     * 且字段清单中命中的分区列被标记 isPartition=1。
     */
    @Test
    void testSyncSingleTableMetadata_partitionAndCompressionPersisted() {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, "tmall_dw", "ods_order")).thenReturn(null);

        ColumnMetadataVO partitionCol = new ColumnMetadataVO();
        partitionCol.setColumnName("dt");
        partitionCol.setColumnType("STRING");
        partitionCol.setOrdinalPosition(1);
        ColumnMetadataVO normalCol = new ColumnMetadataVO();
        normalCol.setColumnName("order_id");
        normalCol.setColumnType("BIGINT");
        normalCol.setOrdinalPosition(2);
        when(executionStrategy.listColumns(eq(mockConfig), eq("tmall_dw"), eq("ods_order")))
                .thenReturn(new ArrayList<>(Arrays.asList(partitionCol, normalCol)));

        AbstractBaseExecutionStrategy.HiveTableAttributes attrs =
                new AbstractBaseExecutionStrategy.HiveTableAttributes();
        attrs.partitionColumns = "dt STRING, `region`";
        attrs.compressionCodec = "SNAPPY";

        assertTrue(metadataSyncService.syncSingleTableMetadata(
                1L, DatasourceType.HIVE, "tmall_dw", "ods_order", "CREATE", attrs));

        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(table -> table != null
                        && "dt,region".equals(table.getPartitionColumns())
                        && "SNAPPY".equals(table.getCompression())),
                argThat(cols -> cols != null && cols.size() == 2
                        && Integer.valueOf(1).equals(cols.get(0).getIsPartition())
                        && Integer.valueOf(0).equals(cols.get(1).getIsPartition())));
    }

    /**
     * Kyuubi 路径：表级分区列缺失时，从字段 isPartition 标记反哺 table_metadata.partition_columns。
     */
    @Test
    void testSyncSingleTableMetadata_backfillPartitionColumnsFromColumnFlag() {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, "ods", "partitioned_tbl")).thenReturn(null);

        ColumnMetadataVO normalCol = new ColumnMetadataVO();
        normalCol.setColumnName("id");
        normalCol.setColumnType("BIGINT");
        normalCol.setOrdinalPosition(1);
        ColumnMetadataVO partitionCol = new ColumnMetadataVO();
        partitionCol.setColumnName("dt");
        partitionCol.setColumnType("STRING");
        partitionCol.setOrdinalPosition(2);
        partitionCol.setIsPartition(1);
        when(executionStrategy.listColumns(eq(mockConfig), eq("ods"), eq("partitioned_tbl")))
                .thenReturn(new ArrayList<>(Arrays.asList(normalCol, partitionCol)));

        assertTrue(metadataSyncService.syncSingleTableMetadata(
                1L, DatasourceType.HIVE, "ods", "partitioned_tbl", "CREATE", null));

        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(table -> table != null && "dt".equals(table.getPartitionColumns())),
                anyList());
    }

    /** merge 应复制分区列与最近修改时间（统计采集新增的两个字段） */
    @Test
    void testMergeTableMetadata_partitionAndLastModifyTime() throws Exception {
        java.lang.reflect.Method method = MetadataSyncService.class.getDeclaredMethod("mergeTableMetadata",
                TableMetadata.class, TableMetadata.class);
        method.setAccessible(true);

        TableMetadata target = new TableMetadata();
        TableMetadata source = new TableMetadata();
        source.setPartitionColumns("dt,region");
        Date modifyTime = new Date(1712345678000L);
        source.setLastModifyTime(modifyTime);

        method.invoke(metadataSyncService, target, source);

        assertEquals("dt,region", target.getPartitionColumns());
        assertEquals(modifyTime, target.getLastModifyTime());
    }

    /** 分区列文本规范化：Hive 类型声明、Doris 反引号、ClickHouse 表达式、带参数类型 */
    @Test
    void testNormalizePartitionColumns() {
        assertEquals("dt,region", MetadataSyncService.normalizePartitionColumns("dt STRING, `region`"));
        assertEquals("dt", MetadataSyncService.normalizePartitionColumns("dt decimal(10,2)"));
        assertEquals("toYYYYMM(event_date)", MetadataSyncService.normalizePartitionColumns("toYYYYMM(event_date)"));
        assertEquals("k1,k2", MetadataSyncService.normalizePartitionColumns("`k1`, `k2`"));
        assertNull(MetadataSyncService.normalizePartitionColumns("  "));
        assertNull(MetadataSyncService.normalizePartitionColumns(null));
    }

    // ==================== CREATE EXTERNAL TABLE DDL 元数据同步（真实数据）====================

    /** 用户提供的真实建表SQL：tmall_dw.ods_product 外部表 */
    private static final String CREATE_EXTERNAL_TABLE_SQL =
            "CREATE EXTERNAL TABLE IF NOT EXISTS tmall_dw.ods_product (\n" +
            "     item_id    BIGINT  COMMENT '商品 ID',\n" +
            "     title      STRING  COMMENT '商品标题关键词（NLP 提取，空格分隔）',\n" +
            "     pict_url   STRING  COMMENT '商品主图 URL',\n" +
            "   category   STRING  COMMENT '商品类目编码，格式：父类目-叶子类目',\n" +
            "   brand_id   STRING  COMMENT '品牌 ID',\n" +
            "   seller_id  STRING  COMMENT '卖家 ID'\n" +
            ") \n" +
            "ROW FORMAT DELIMITED \n" +
            "FIELDS TERMINATED BY '\\001' \n" +
            "STORED AS TEXTFILE \n" +
            "LOCATION 'jfs://myjfs/test/product' \n" +
            "TBLPROPERTIES ( \n" +
            "    'comment' = '天猫商品属性原始数据外部表', \n" +
            "    'skip.header.line.count' = '0' \n" +
            ");";

    private static final String DB_NAME = "tmall_dw";
    private static final String TABLE_NAME = "ods_product";
    private static final Long DATASOURCE_ID = 1L;

    /**
     * 构造 ods_product 表的6个真实字段VO（来自CREATE TABLE语句定义）
     */
    private List<ColumnMetadataVO> buildOdsProductColumns() {
        List<ColumnMetadataVO> columns = new ArrayList<>();

        ColumnMetadataVO col1 = new ColumnMetadataVO();
        col1.setTableName(TABLE_NAME);
        col1.setColumnName("item_id");
        col1.setColumnType("BIGINT");
        col1.setColumnComment("商品 ID");
        col1.setOrdinalPosition(1);
        col1.setNullable(0);
        columns.add(col1);

        ColumnMetadataVO col2 = new ColumnMetadataVO();
        col2.setTableName(TABLE_NAME);
        col2.setColumnName("title");
        col2.setColumnType("STRING");
        col2.setColumnComment("商品标题关键词（NLP 提取，空格分隔）");
        col2.setOrdinalPosition(2);
        col2.setNullable(1);
        columns.add(col2);

        ColumnMetadataVO col3 = new ColumnMetadataVO();
        col3.setTableName(TABLE_NAME);
        col3.setColumnName("pict_url");
        col3.setColumnType("STRING");
        col3.setColumnComment("商品主图 URL");
        col3.setOrdinalPosition(3);
        col3.setNullable(1);
        columns.add(col3);

        ColumnMetadataVO col4 = new ColumnMetadataVO();
        col4.setTableName(TABLE_NAME);
        col4.setColumnName("category");
        col4.setColumnType("STRING");
        col4.setColumnComment("商品类目编码，格式：父类目-叶子类目");
        col4.setOrdinalPosition(4);
        col4.setNullable(1);
        columns.add(col4);

        ColumnMetadataVO col5 = new ColumnMetadataVO();
        col5.setTableName(TABLE_NAME);
        col5.setColumnName("brand_id");
        col5.setColumnType("STRING");
        col5.setColumnComment("品牌 ID");
        col5.setOrdinalPosition(5);
        col5.setNullable(1);
        columns.add(col5);

        ColumnMetadataVO col6 = new ColumnMetadataVO();
        col6.setTableName(TABLE_NAME);
        col6.setColumnName("seller_id");
        col6.setColumnType("STRING");
        col6.setColumnComment("卖家 ID");
        col6.setOrdinalPosition(6);
        col6.setNullable(1);
        columns.add(col6);

        return columns;
    }

    /**
     * 测试1: DDL检测 — 验证JSqlParser能正确解析CREATE EXTERNAL TABLE语句，
     * 提取出 databaseName=tmall_dw, tableName=ods_product, ddlType=CREATE
     */
    @Test
    void testDetectDdlStatement_CreateExternalTable() throws Exception {
        java.lang.reflect.Method detectMethod = AbstractBaseExecutionStrategy.class.getDeclaredMethod(
                "detectDdlStatement", String.class, DatasourceType.class);
        detectMethod.setAccessible(true);

        // 创建一个可测试的具体子类，实现getLog()返回真实Logger
        TestableBaseStrategy strategy = new TestableBaseStrategy();

        // 通过detectDdlStatement调用（内部会先stripHiveSpecificClauses再解析）
        Object result = detectMethod.invoke(strategy, CREATE_EXTERNAL_TABLE_SQL, DatasourceType.HIVE);

        assertNotNull(result, "CREATE EXTERNAL TABLE 应被识别为DDL语句");

        // 通过反射读取DdlInfo内部类的字段
        Class<?> ddlInfoClass = result.getClass();
        java.lang.reflect.Field ddlTypeField = ddlInfoClass.getDeclaredField("ddlType");
        java.lang.reflect.Field dbNameField = ddlInfoClass.getDeclaredField("databaseName");
        java.lang.reflect.Field tblNameField = ddlInfoClass.getDeclaredField("tableName");
        ddlTypeField.setAccessible(true);
        dbNameField.setAccessible(true);
        tblNameField.setAccessible(true);

        assertEquals("CREATE", ddlTypeField.get(result), "DDL类型应为CREATE");
        assertEquals(DB_NAME, dbNameField.get(result), "数据库名应为tmall_dw");
        assertEquals(TABLE_NAME, tblNameField.get(result), "表名应为ods_product");
    }

    /**
     * 测试2: 完整DDL元数据同步流程 — 模拟handleDdlMetadataSync调用syncSingleTableMetadata，
     * 验证table_metadata和column_metadata均被正确写入。
     *
     * 场景：表不存在（首次建表），应走insert逻辑
     */
    @Test
    void testCreateExternalTableDdlSync_newTable() {
        // 1. Mock数据源配置查询
        when(datasourceConfigService.getDatasourceById(DATASOURCE_ID)).thenReturn(mockConfig);

        // 2. Mock ExecutionStrategy工厂和listColumns返回6个真实字段
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listColumns(eq(mockConfig), eq(DB_NAME), eq(TABLE_NAME)))
                .thenReturn(buildOdsProductColumns());

        // 3. Mock表元数据查询：表不存在（首次建表），返回null → 走新建逻辑
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, DB_NAME, TABLE_NAME))
                .thenReturn(null);

        // 4. 执行同步（模拟handleDdlMetadataSync的调用）
        boolean result = metadataSyncService.syncSingleTableMetadata(
                DATASOURCE_ID, DatasourceType.HIVE, DB_NAME, TABLE_NAME, "CREATE", null);

        // 5. 断言同步成功
        assertTrue(result, "CREATE TABLE同步应成功");

        // 6. 验证tableMetadataService.syncSingleTable被调用，且传入了正确的表+列数据
        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(table -> {
                    // 验证表基本信息
                    if (table == null) return false;
                    if (!TABLE_NAME.equals(table.getTableName())) return false;
                    if (!DB_NAME.equals(table.getDatabaseName())) return false;
                    if (table.getDatasourceId() == null || !DATASOURCE_ID.equals(table.getDatasourceId())) return false;
                    return true;
                }),
                argThat(columns -> {
                    // 验证6个字段全部写入column_metadata
                    if (columns == null || columns.size() != 6) return false;
                    // 验证每个字段的名称、类型、注释、顺序
                    String[] expectedNames = {"item_id", "title", "pict_url", "category", "brand_id", "seller_id"};
                    String[] expectedTypes = {"BIGINT", "STRING", "STRING", "STRING", "STRING", "STRING"};
                    String[] expectedComments = {
                            "商品 ID",
                            "商品标题关键词（NLP 提取，空格分隔）",
                            "商品主图 URL",
                            "商品类目编码，格式：父类目-叶子类目",
                            "品牌 ID",
                            "卖家 ID"
                    };
                    for (int i = 0; i < 6; i++) {
                        ColumnMetadata col = columns.get(i);
                        if (!expectedNames[i].equals(col.getColumnName())) return false;
                        if (!expectedTypes[i].equals(col.getColumnType())) return false;
                        if (!expectedComments[i].equals(col.getColumnComment())) return false;
                        if ((i + 1) != col.getOrdinalPosition()) return false;
                        if (!DB_NAME.equals(col.getDatabaseName())) return false;
                        if (!TABLE_NAME.equals(col.getTableName())) return false;
                        if (col.getSyncTime() == null) return false; // 同步时间必须设置
                    }
                    return true;
                })
        );
    }

    /**
     * 测试3: DDL同步 — 表已存在场景（ALTER/重建表时覆盖更新）
     */
    @Test
    void testCreateExternalTableDdlSync_existingTable() {
        when(datasourceConfigService.getDatasourceById(DATASOURCE_ID)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listColumns(eq(mockConfig), eq(DB_NAME), eq(TABLE_NAME)))
                .thenReturn(buildOdsProductColumns());

        // 表已存在，返回旧记录
        TableMetadata existingTable = new TableMetadata();
        existingTable.setId(200L);
        existingTable.setTableName(TABLE_NAME);
        existingTable.setDatabaseName(DB_NAME);
        existingTable.setTableComment("旧的注释");
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, DB_NAME, TABLE_NAME))
                .thenReturn(existingTable);

        boolean result = metadataSyncService.syncSingleTableMetadata(
                DATASOURCE_ID, DatasourceType.HIVE, DB_NAME, TABLE_NAME, "CREATE", null);

        assertTrue(result);

        // 已有表时，targetTable应该是已有的existingTable（id=200L），而非新建
        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(table -> table != null && table.getId() != null && table.getId().equals(200L)),
                anyList()
        );
    }

    /**
     * 测试4: 端到端 — 模拟AbstractBaseExecutionStrategy.handleDdlMetadataSync完整调用链。
     *
     * 验证从SQL解析 → DDL检测 → 调用syncSingleTableMetadata → 写入table_metadata + column_metadata 的完整链路。
     * 使用反射调用handleDdlMetadataSync（protected方法）。
     */
    @Test
    void testHandleDdlMetadataSync_endToEnd() throws Exception {
        // ---- 准备Mock ----
        when(datasourceConfigService.getDatasourceById(DATASOURCE_ID)).thenReturn(mockConfig);
        mockConfig.setDatasourceType(DatasourceType.HIVE);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listColumns(eq(mockConfig), eq(DB_NAME), eq(TABLE_NAME)))
                .thenReturn(buildOdsProductColumns());
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, DB_NAME, TABLE_NAME))
                .thenReturn(null);

        // 构造CodeExecutionContext
        CodeExecutionContext context = new CodeExecutionContext();
        context.setExecutionId("exec-test-001");
        context.setCodeContent(CREATE_EXTERNAL_TABLE_SQL);
        context.setDatasourceConfigs(Collections.singletonList(mockConfig));

        // 构造可测试的具体子类并注入mock依赖
        TestableBaseStrategy strategy = new TestableBaseStrategy();
        java.lang.reflect.Field msField = AbstractBaseExecutionStrategy.class.getDeclaredField("metadataSyncService");
        msField.setAccessible(true);
        msField.set(strategy, metadataSyncService);

        java.lang.reflect.Field logListenerField = AbstractBaseExecutionStrategy.class.getDeclaredField("executionLogListener");
        logListenerField.setAccessible(true);
        com.bigdata.modules.development.service.execution.ExecutionLogListener mockLogListener =
                mock(com.bigdata.modules.development.service.execution.ExecutionLogListener.class);
        logListenerField.set(strategy, mockLogListener);

        // ---- 执行 ----
        java.lang.reflect.Method handleDdlMethod = AbstractBaseExecutionStrategy.class.getDeclaredMethod(
                "handleDdlMetadataSync", String.class, CodeExecutionContext.class, String.class);
        handleDdlMethod.setAccessible(true);
        handleDdlMethod.invoke(strategy, CREATE_EXTERNAL_TABLE_SQL, context, "exec-test-001");

        // ---- 验证 ----
        // 1. executionLogListener发布了DDL日志
        verify(mockLogListener).publishLog(eq("exec-test-001"), contains("DDL"));
        verify(mockLogListener, atLeastOnce()).publishLog(eq("exec-test-001"), contains("ods_product"));

        // 2. metadataSyncService.syncSingleTableMetadata被调用（通过内部调用链验证）
        //    由于metadataSyncService是@InjectMocks真实对象，无法直接verify
        //    改为验证其最终调用的tableMetadataService.syncSingleTable参数正确

        // 3. 最终写入了table_metadata + column_metadata（6个字段）
        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(t -> t != null && TABLE_NAME.equals(t.getTableName())),
                argThat(cols -> cols != null && cols.size() == 6)
        );

        // 4. 发布了成功日志
        verify(mockLogListener).publishLog(eq("exec-test-001"), contains("同步成功"));
    }

    /**
     * 测试5: 字段完整性验证 — 逐个校验6个字段的每个属性是否与DDL定义一致
     */
    @Test
    void testCreateExternalTableDdlSync_columnDataIntegrity() throws Exception {
        when(datasourceConfigService.getDatasourceById(DATASOURCE_ID)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listColumns(eq(mockConfig), eq(DB_NAME), eq(TABLE_NAME)))
                .thenReturn(buildOdsProductColumns());
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, DB_NAME, TABLE_NAME))
                .thenReturn(null);

        metadataSyncService.syncSingleTableMetadata(
                DATASOURCE_ID, DatasourceType.HIVE, DB_NAME, TABLE_NAME, "CREATE", null);

        // 捕获实际传入syncSingleTable的columns参数
        java.util.ArrayList<List<ColumnMetadata>> capturedColumns = new java.util.ArrayList<>();
        verify(tableMetadataService).syncSingleTable(any(), any(), org.mockito.ArgumentMatchers.<List<ColumnMetadata>>argThat(capturedColumns::add));

        List<ColumnMetadata> columns = capturedColumns.get(0);
        assertEquals(6, columns.size(), "应有6个字段");

        // === 字段1: item_id BIGINT COMMENT '商品 ID' ===
        ColumnMetadata c0 = columns.get(0);
        assertEquals("item_id", c0.getColumnName());
        assertEquals("BIGINT", c0.getColumnType());
        assertEquals("商品 ID", c0.getColumnComment());
        assertEquals(1, c0.getOrdinalPosition());
        assertEquals(0, c0.getNullable());       // BIGINT主键通常NOT NULL
        assertEquals(0, c0.getIsPartition());     // 非分区列
        assertEquals(0, c0.getIsPrimaryKey());    // 未显式声明PK
        assertNotNull(c0.getSyncTime());

        // === 字段2: title STRING COMMENT '商品标题关键词...' ===
        ColumnMetadata c1 = columns.get(1);
        assertEquals("title", c1.getColumnName());
        assertEquals("STRING", c1.getColumnType());
        assertEquals("商品标题关键词（NLP 提取，空格分隔）", c1.getColumnComment());
        assertEquals(2, c1.getOrdinalPosition());

        // === 字段3: pict_url STRING COMMENT '商品主图 URL' ===
        ColumnMetadata c2 = columns.get(2);
        assertEquals("pict_url", c2.getColumnName());
        assertEquals("STRING", c2.getColumnType());
        assertEquals("商品主图 URL", c2.getColumnComment());
        assertEquals(3, c2.getOrdinalPosition());

        // === 字段4: category STRING COMMENT '商品类目编码...' ===
        ColumnMetadata c3 = columns.get(3);
        assertEquals("category", c3.getColumnName());
        assertEquals("STRING", c3.getColumnType());
        assertEquals("商品类目编码，格式：父类目-叶子类目", c3.getColumnComment());
        assertEquals(4, c3.getOrdinalPosition());

        // === 字段5: brand_id STRING COMMENT '品牌 ID' ===
        ColumnMetadata c4 = columns.get(4);
        assertEquals("brand_id", c4.getColumnName());
        assertEquals("STRING", c4.getColumnType());
        assertEquals("品牌 ID", c4.getColumnComment());
        assertEquals(5, c4.getOrdinalPosition());

        // === 字段6: seller_id STRING COMMENT '卖家 ID' ===
        ColumnMetadata c5 = columns.get(5);
        assertEquals("seller_id", c5.getColumnName());
        assertEquals("STRING", c5.getColumnType());
        assertEquals("卖家 ID", c5.getColumnComment());
        assertEquals(6, c5.getOrdinalPosition());
    }

    /**
     * 测试6: Hive表级属性提取与写入验证 — 核心测试！
     *
     * 验证从 CREATE EXTERNAL TABLE DDL 中提取的以下属性正确写入 table_metadata：
     * - EXTERNAL → tableType = "EXTERNAL_TABLE"
     * - TBLPROPERTIES 'comment' → tableComment = "天猫商品属性原始数据外部表"
     * - STORED AS TEXTFILE → storageFormat = "TEXTFILE"
     * - LOCATION 'jfs://myjfs/test/product' → location = "jfs://myjfs/test/product"
     */
    @Test
    void testCreateExternalTableDdlSync_hiveAttributesExtracted() throws Exception {
        // 1. 准备Mock
        when(datasourceConfigService.getDatasourceById(DATASOURCE_ID)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);
        when(executionStrategy.listColumns(eq(mockConfig), eq(DB_NAME), eq(TABLE_NAME)))
                .thenReturn(buildOdsProductColumns());
        when(tableMetadataMapper.getTableMetadata(DatasourceType.HIVE, DB_NAME, TABLE_NAME))
                .thenReturn(null);

        // 2. 构造真实的HiveTableAttributes（模拟extractAndStripHiveAttributes的提取结果）
        AbstractBaseExecutionStrategy.HiveTableAttributes hiveAttrs =
                new AbstractBaseExecutionStrategy.HiveTableAttributes();
        hiveAttrs.tableType = "EXTERNAL_TABLE";
        hiveAttrs.tableComment = "天猫商品属性原始数据外部表";
        hiveAttrs.storageFormat = "TEXTFILE";
        hiveAttrs.location = "jfs://myjfs/test/product";
        hiveAttrs.fieldDelimiter = "\\001";

        // 3. 执行同步，传入真实的Hive属性
        boolean result = metadataSyncService.syncSingleTableMetadata(
                DATASOURCE_ID, DatasourceType.HIVE, DB_NAME, TABLE_NAME, "CREATE", hiveAttrs);
        assertTrue(result);

        // 4. 验证table_metadata中写入了所有Hive属性
        verify(tableMetadataService).syncSingleTable(
                eq(DatasourceType.HIVE),
                argThat(table -> {
                    if (table == null) return false;
                    // === 验证表类型 ===
                    if (!"EXTERNAL_TABLE".equals(table.getTableType())) {
                        System.out.println("[ASSERT-FAIL] tableType 期望=EXTERNAL_TABLE, 实际=" + table.getTableType());
                        return false;
                    }
                    // === 验证表注释（来自TBLPROPERTIES 'comment'）===
                    if (!"天猫商品属性原始数据外部表".equals(table.getTableComment())) {
                        System.out.println("[ASSERT-FAIL] tableComment 期望=天猫商品属性原始数据外部表, 实际=" + table.getTableComment());
                        return false;
                    }
                    // === 验证存储格式（来自STORED AS）===
                    if (!"TEXTFILE".equals(table.getStorageFormat())) {
                        System.out.println("[ASSERT-FAIL] storageFormat 期望=TEXTFILE, 实际=" + table.getStorageFormat());
                        return false;
                    }
                    // === 验证存储路径（来自LOCATION）===
                    if (!"jfs://myjfs/test/product".equals(table.getLocation())) {
                        System.out.println("[ASSERT-FAIL] location 期望=jfs://myjfs/test/product, 实际=" + table.getLocation());
                        return false;
                    }
                    // === 基础字段也需正确 ===
                    if (!TABLE_NAME.equals(table.getTableName())) return false;
                    if (!DB_NAME.equals(table.getDatabaseName())) return false;
                    if (table.getDatasourceId() == null || !DATASOURCE_ID.equals(table.getDatasourceId())) return false;
                    return true;
                }),
                anyList()
        );
    }

    /**
     * 测试7: Hive表级属性提取验证 — 确认CalciteDdlParser的正则提取逻辑正确性
     */
    @Test
    void testExtractHiveAttributes_fullExtraction() {
        // 直接调用 CalciteDdlParser（无需反射）
        CalciteDdlParser.TableAttributes attrs = CalciteDdlParser.extractHiveAttributes(CREATE_EXTERNAL_TABLE_SQL);

        assertNotNull(attrs, "Hive属性不应为null");

        // === 验证提取的Hive属性 ===
        assertEquals("EXTERNAL_TABLE", attrs.tableType,
                "EXTERNAL关键字应被识别为EXTERNAL_TABLE");
        assertTrue(attrs.isExternal, "isExternal应为true");
        assertEquals("天猫商品属性原始数据外部表", attrs.tableComment,
                "TBLPROPERTIES comment应被提取为表注释");
        assertEquals("TEXTFILE", attrs.storageFormat,
                "STORED AS TEXTFILE应被提取");
        assertEquals("jfs://myjfs/test/product", attrs.location,
                "LOCATION路径应被提取");
        assertEquals("\\001", attrs.fieldDelimiter,
                "FIELDS TERMINATED BY分隔符应被提取");
        assertNotNull(attrs.tblProperties, "tblProperties不应为null");
        assertEquals("天猫商品属性原始数据外部表", attrs.tblProperties.get("comment"),
                "tblProperties中应包含comment键值对");
        assertEquals("0", attrs.tblProperties.get("skip.header.line.count"),
                "tblProperties中应包含其他属性");
    }

    /**
     * 测试8: CalciteDdlParser.detect() 完整解析验证 — CREATE EXTERNAL TABLE
     */
    @Test
    void testCalciteDdlParser_detectCreateExternalTable() {
        CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(CREATE_EXTERNAL_TABLE_SQL);

        assertNotNull(info, "CREATE EXTERNAL TABLE 应被识别为DDL语句");
        assertEquals("CREATE", info.getDbType(), "DDL类型应为CREATE");
        assertEquals(DB_NAME, info.getDatabaseName(), "数据库名应为tmall_dw");
        assertEquals(TABLE_NAME, info.getTableName(), "表名应为ods_product");

        // 验证Hive属性也被正确提取
        assertNotNull(info.getHiveAttributes(), "Hive属性不应为null");
        assertEquals("EXTERNAL_TABLE", info.getHiveAttributes().tableType);
        assertEquals("天猫商品属性原始数据外部表", info.getHiveAttributes().tableComment);
        assertEquals("TEXTFILE", info.getHiveAttributes().storageFormat);
    }

    /**
     * 可测试的AbstractBaseExecutionStrategy具体子类，仅实现getLog()抽象方法。
     * 用于单元测试中调用protected方法（detectDdlStatement、handleDdlMetadataSync等）。
     */
    private static class TestableBaseStrategy extends AbstractBaseExecutionStrategy {
        @Override
        protected org.slf4j.Logger getLog() {
            return org.slf4j.LoggerFactory.getLogger(TestableBaseStrategy.class);
        }

        @Override
        public com.bigdata.modules.devops.enums.DatasourceType getEngineType() {
            return null;
        }

        @Override
        public com.bigdata.modules.development.service.execution.CodeExecutionResult execute(
                CodeExecutionContext context) {
            return null;
        }
    }
}
