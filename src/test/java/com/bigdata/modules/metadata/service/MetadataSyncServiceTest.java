package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.development.service.execution.ExecutionStrategy;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
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
        mockConfig.setStorageEngine(StorageEngineType.HIVE);
        mockConfig.setDatasourceName("test-kyuubi");
    }

    // ==================== syncSingleTableMetadata 测试 ====================

    @Test
    void testSyncSingleTableMetadata_DROP() {
        boolean result = metadataSyncService.syncSingleTableMetadata(1L, StorageEngineType.HIVE, "ods", "user_info", "DROP");
        assertTrue(result);
        verify(tableMetadataService).deleteTableByNames(StorageEngineType.HIVE, "ods", "user_info");
    }

    @Test
    void testSyncSingleTableMetadata_success() throws Exception {
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(mockConfig);
        when(executionStrategyFactory.getStrategy(mockConfig)).thenReturn(executionStrategy);

        TableMetadata existingTable = new TableMetadata();
        existingTable.setId(100L);
        existingTable.setTableName("user_info");
        when(tableMetadataMapper.getTableMetadata(StorageEngineType.HIVE, "ods", "user_info")).thenReturn(existingTable);

        ColumnMetadataVO colVo = new ColumnMetadataVO();
        colVo.setColumnName("id");
        colVo.setColumnType("BIGINT");
        colVo.setOrdinalPosition(1);
        when(executionStrategy.listColumns(eq(mockConfig), eq("ods"), eq("user_info")))
                .thenReturn(Collections.singletonList(colVo));

        boolean result = metadataSyncService.syncSingleTableMetadata(1L, StorageEngineType.HIVE, "ods", "user_info", "CREATE");
        assertTrue(result);
        verify(tableMetadataService).syncSingleTable(eq(StorageEngineType.HIVE), eq(existingTable), anyList());
    }

    @Test
    void testSyncSingleTableMetadata_configNotFound() {
        when(datasourceConfigService.getDatasourceById(999L)).thenReturn(null);
        boolean result = metadataSyncService.syncSingleTableMetadata(999L, StorageEngineType.HIVE, "ods", "t", "CREATE");
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
                List.class, TableMetadata.class, StorageEngineType.class, String.class);
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
                metadataSyncService, vos, table, StorageEngineType.HIVE, "test_db");

        assertEquals(2, columns.size());

        ColumnMetadata col1 = columns.get(0);
        assertEquals(10L, col1.getTableMetadataId());
        assertEquals(StorageEngineType.HIVE, col1.getStorageEngine());
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
                List.class, TableMetadata.class, StorageEngineType.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ColumnMetadata> result1 = (List<ColumnMetadata>) method.invoke(
                metadataSyncService, null, null, StorageEngineType.HIVE, "db");
        assertTrue(result1.isEmpty());

        @SuppressWarnings("unchecked")
        List<ColumnMetadata> result2 = (List<ColumnMetadata>) method.invoke(
                metadataSyncService, Collections.emptyList(), null, StorageEngineType.HIVE, "db");
        assertTrue(result2.isEmpty());
    }
}
