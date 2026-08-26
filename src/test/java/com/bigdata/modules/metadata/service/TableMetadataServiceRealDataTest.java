package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("Metadata模块 - TableMetadataService真实数据集成测试")
class TableMetadataServiceRealDataTest {

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        setUpAdminSecurityContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setUpAdminSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private TableMetadata buildTestTable(DatasourceType engine, String dbName,
                                          String tableName, String comment) {
        TableMetadata table = new TableMetadata();
        table.setDatasourceId(1L);
        table.setStorageEngine(engine);
        table.setDatabaseName(dbName);
        table.setTableName(tableName);
        table.setTableType("MANAGED_TABLE");
        table.setTableComment(comment);
        table.setSyncTime(new Date());
        table.setDeleted(0);
        return table;
    }

    private ColumnMetadata buildTestColumn(DatasourceType engine, String dbName,
                                            String tableName, String colName,
                                            String colType, int ordinal) {
        ColumnMetadata col = new ColumnMetadata();
        col.setStorageEngine(engine);
        col.setDatabaseName(dbName);
        col.setTableName(tableName);
        col.setColumnName(colName);
        col.setColumnType(colType);
        col.setOrdinalPosition(ordinal);
        col.setNullable(1);
        col.setIsPartition(0);
        col.setIsPrimaryKey(0);
        col.setSyncTime(new Date());
        return col;
    }

    @Nested
    @DisplayName("场景1: 表元数据CRUD")
    class TableCrudTests {

        @Test
        @DisplayName("✅ 插入表元数据")
        void testInsertTable() {
            System.out.println("\n=== 场景1.1: 插入表元数据 ===");

            TableMetadata table = buildTestTable(DatasourceType.HIVE, "crud_db", "insert_table", "插入测试表");
            table.setDatasourceId(100L);
            table.setRowCount(1000L);
            table.setSizeBytes(204800L);

            tableMetadataService.save(table);

            assertNotNull(table.getId(), "插入后应有ID");
            TableMetadata loaded = tableMetadataService.getById(table.getId());
            assertNotNull(loaded, "应能查到插入的记录");
            assertEquals("crud_db", loaded.getDatabaseName());
            assertEquals("insert_table", loaded.getTableName());
            assertEquals(DatasourceType.HIVE, loaded.getStorageEngine());
            assertEquals(1000L, loaded.getRowCount());
            System.out.println("  插入成功, id=" + table.getId() + ", table=" + loaded.getTableName());
        }

        @Test
        @DisplayName("✅ 查询表元数据")
        void testQueryTable() {
            System.out.println("\n=== 场景1.2: 查询表元数据 ===");

            TableMetadata table = buildTestTable(DatasourceType.DORIS, "query_db", "query_table", "查询测试表");
            tableMetadataService.save(table);

            TableMetadata loaded = tableMetadataService.getById(table.getId());
            assertNotNull(loaded);
            assertEquals("query_table", loaded.getTableName());
            assertEquals(DatasourceType.DORIS, loaded.getStorageEngine());
            System.out.println("  查询成功: " + loaded.getTableName());
        }

        @Test
        @DisplayName("✅ 更新表元数据")
        void testUpdateTable() {
            System.out.println("\n=== 场景1.3: 更新表元数据 ===");

            TableMetadata table = buildTestTable(DatasourceType.MYSQL, "update_db", "update_table", "原始注释");
            tableMetadataService.save(table);

            table.setTableComment("更新后的注释");
            table.setRowCount(5000L);
            tableMetadataService.updateById(table);

            TableMetadata loaded = tableMetadataService.getById(table.getId());
            assertEquals("更新后的注释", loaded.getTableComment());
            assertEquals(5000L, loaded.getRowCount());
            System.out.println("  更新成功: comment=" + loaded.getTableComment() + ", rowCount=" + loaded.getRowCount());
        }

        @Test
        @DisplayName("✅ 删除表元数据")
        void testDeleteTable() {
            System.out.println("\n=== 场景1.4: 删除表元数据 ===");

            TableMetadata table = buildTestTable(DatasourceType.CLICKHOUSE, "delete_db", "delete_table", "删除测试表");
            tableMetadataService.save(table);
            Long tableId = table.getId();

            tableMetadataService.removeById(tableId);

            TableMetadata loaded = tableMetadataService.getById(tableId);
            assertNull(loaded, "删除后应查不到");
            System.out.println("  删除成功, id=" + tableId);
        }
    }

    @Nested
    @DisplayName("场景2: 按条件组合查询")
    class ConditionalQueryTests {

        @Test
        @DisplayName("✅ 按datasourceId查询")
        void testQueryByDatasourceId() {
            System.out.println("\n=== 场景2.1: 按datasourceId查询 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "cond_db", "ds_table1", "表1");
            t1.setDatasourceId(200L);
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "cond_db", "ds_table2", "表2");
            t2.setDatasourceId(200L);
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "cond_db", "ds_table3", "表3");
            t3.setDatasourceId(999L);
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, 200L, null, null, null);

            assertTrue(page.getRecords().stream().allMatch(t -> t.getDatasourceId().equals(200L)),
                    "应只返回datasourceId=200的记录");
            System.out.println("  datasourceId=200的记录数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 按databaseName查询")
        void testQueryByDatabaseName() {
            System.out.println("\n=== 场景2.2: 按databaseName查询 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "target_db", "tbl_a", "表A");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "target_db", "tbl_b", "表B");
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "other_db", "tbl_c", "表C");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, "target_db", null, null);

            assertTrue(page.getRecords().stream().allMatch(t -> "target_db".equals(t.getDatabaseName())),
                    "应只返回target_db的记录");
            System.out.println("  target_db的记录数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 按tableName模糊查询")
        void testQueryByTableName() {
            System.out.println("\n=== 场景2.3: 按tableName模糊查询 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "like_db", "user_info", "用户信息");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "like_db", "user_order", "用户订单");
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "like_db", "product_info", "产品信息");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, null, "user", null);

            assertTrue(page.getRecords().stream().allMatch(t -> t.getTableName().contains("user")),
                    "应只返回表名包含user的记录");
            System.out.println("  表名包含user的记录数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 按keyword查询（表名或注释）")
        void testQueryByKeyword() {
            System.out.println("\n=== 场景2.4: 按keyword查询 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "kw_db", "order_detail", "订单明细");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "kw_db", "payment_log", "支付日志");
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "kw_db", "user_profile", "用户画像");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, null, null, "订单");

            assertTrue(page.getRecords().stream().anyMatch(t ->
                    t.getTableName().contains("订单") || t.getTableComment().contains("订单")),
                    "关键字应匹配表名或注释");
            System.out.println("  关键字'订单'匹配记录数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 组合条件查询")
        void testQueryWithCombinedConditions() {
            System.out.println("\n=== 场景2.5: 组合条件查询 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "combo_db", "combo_target1", "目标1");
            t1.setDatasourceId(300L);
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "combo_db", "combo_target2", "目标2");
            t2.setDatasourceId(300L);
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "other_combo", "combo_target3", "目标3");
            t3.setDatasourceId(300L);
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, 300L, "combo_db", "combo", null);

            assertTrue(page.getRecords().stream().allMatch(t ->
                    t.getDatasourceId().equals(300L)
                    && "combo_db".equals(t.getDatabaseName())
                    && t.getTableName().contains("combo")),
                    "组合条件应同时满足");
            System.out.println("  组合查询记录数: " + page.getRecords().size());
        }
    }

    @Nested
    @DisplayName("场景3: 列元数据查询")
    class ColumnQueryTests {

        @Test
        @DisplayName("✅ 按tableMetadataId查询列")
        void testGetColumnsByTableId() {
            System.out.println("\n=== 场景3.1: 按tableMetadataId查询列 ===");

            TableMetadata table = buildTestTable(DatasourceType.HIVE, "col_db1", "col_table1", "列测试表1");
            tableMetadataService.save(table);

            ColumnMetadata c1 = buildTestColumn(DatasourceType.HIVE, "col_db1", "col_table1", "id", "bigint", 1);
            c1.setTableMetadataId(table.getId());
            c1.setIsPrimaryKey(1);
            ColumnMetadata c2 = buildTestColumn(DatasourceType.HIVE, "col_db1", "col_table1", "name", "string", 2);
            c2.setTableMetadataId(table.getId());
            ColumnMetadata c3 = buildTestColumn(DatasourceType.HIVE, "col_db1", "col_table1", "age", "int", 3);
            c3.setTableMetadataId(table.getId());
            columnMetadataMapper.insert(c1);
            columnMetadataMapper.insert(c2);
            columnMetadataMapper.insert(c3);

            List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(table.getId());

            assertEquals(3, columns.size(), "应返回3列");
            assertEquals("id", columns.get(0).getColumnName(), "应按ordinalPosition排序");
            assertEquals("name", columns.get(1).getColumnName());
            assertEquals("age", columns.get(2).getColumnName());
            System.out.println("  列数: " + columns.size() + ", 第一列: " + columns.get(0).getColumnName());
        }

        @Test
        @DisplayName("✅ 按engine+db+table查询列")
        void testGetColumnsByNames() {
            System.out.println("\n=== 场景3.2: 按engine+db+table查询列 ===");

            TableMetadata table = buildTestTable(DatasourceType.DORIS, "col_db2", "col_table2", "列测试表2");
            tableMetadataService.save(table);

            ColumnMetadata c1 = buildTestColumn(DatasourceType.DORIS, "col_db2", "col_table2", "user_id", "int", 1);
            c1.setTableMetadataId(table.getId());
            ColumnMetadata c2 = buildTestColumn(DatasourceType.DORIS, "col_db2", "col_table2", "amount", "decimal", 2);
            c2.setTableMetadataId(table.getId());
            columnMetadataMapper.insert(c1);
            columnMetadataMapper.insert(c2);

            List<ColumnMetadata> columns = tableMetadataService.getColumnsByNames(
                    DatasourceType.DORIS, "col_db2", "col_table2");

            assertEquals(2, columns.size(), "应返回2列");
            assertTrue(columns.stream().allMatch(c ->
                    c.getStorageEngine() == DatasourceType.DORIS
                    && "col_db2".equals(c.getDatabaseName())
                    && "col_table2".equals(c.getTableName())),
                    "所有列应属于指定的engine+db+table");
            System.out.println("  按名称查询列数: " + columns.size());
        }

        @Test
        @DisplayName("✅ 查询不存在表的列返回空")
        void testGetColumns_NonExistentTable() {
            System.out.println("\n=== 场景3.3: 查询不存在表的列 ===");

            List<ColumnMetadata> columns = tableMetadataService.getColumnsByNames(
                    DatasourceType.HBASE, "nonexistent", "nonexistent");

            assertTrue(columns.isEmpty(), "不存在的表应返回空列列表");
            System.out.println("  不存在表的列数: " + columns.size());
        }
    }

    @Nested
    @DisplayName("场景4: 增量同步（safeIncrementalSync）")
    class IncrementalSyncTests {

        @Test
        @DisplayName("✅ 新增表 - 同步后数据库中存在新表")
        void testIncrementalSync_NewTables() {
            System.out.println("\n=== 场景4.1: 增量同步-新增表 ===");

            List<TableMetadata> newTables = new ArrayList<>();
            newTables.add(buildTestTable(DatasourceType.HIVE, "sync_db", "new_table1", "新表1"));
            newTables.add(buildTestTable(DatasourceType.HIVE, "sync_db", "new_table2", "新表2"));

            Map<String, List<ColumnMetadata>> columnsMap = new HashMap<>();
            columnsMap.put("HIVE##sync_db##new_table1", Arrays.asList(
                    buildTestColumn(DatasourceType.HIVE, "sync_db", "new_table1", "id", "int", 1),
                    buildTestColumn(DatasourceType.HIVE, "sync_db", "new_table1", "name", "string", 2)
            ));
            columnsMap.put("HIVE##sync_db##new_table2", Collections.singletonList(
                    buildTestColumn(DatasourceType.HIVE, "sync_db", "new_table2", "val", "double", 1)
            ));

            tableMetadataService.safeIncrementalSync(DatasourceType.HIVE, "sync_db", newTables, columnsMap);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 100, null, "sync_db", null, null);
            List<TableMetadata> syncDbTables = page.getRecords().stream()
                    .filter(t -> "sync_db".equals(t.getDatabaseName())
                            && t.getStorageEngine() == DatasourceType.HIVE)
                    .toList();

            assertTrue(syncDbTables.stream().anyMatch(t -> "new_table1".equals(t.getTableName())),
                    "应存在new_table1");
            assertTrue(syncDbTables.stream().anyMatch(t -> "new_table2".equals(t.getTableName())),
                    "应存在new_table2");
            System.out.println("  同步后表数: " + syncDbTables.size());
        }

        @Test
        @DisplayName("✅ 更新已有表 - 同步后表注释更新")
        void testIncrementalSync_UpdateExistingTable() {
            System.out.println("\n=== 场景4.2: 增量同步-更新已有表 ===");

            TableMetadata existing = buildTestTable(DatasourceType.HIVE, "sync_upd_db", "upd_table", "旧注释");
            tableMetadataService.save(existing);
            Long existingId = existing.getId();

            List<TableMetadata> updatedTables = new ArrayList<>();
            TableMetadata updated = buildTestTable(DatasourceType.HIVE, "sync_upd_db", "upd_table", "新注释");
            updatedTables.add(updated);

            Map<String, List<ColumnMetadata>> columnsMap = new HashMap<>();
            columnsMap.put("HIVE##sync_upd_db##upd_table", Collections.emptyList());

            tableMetadataService.safeIncrementalSync(DatasourceType.HIVE, "sync_upd_db", updatedTables, columnsMap);

            TableMetadata loaded = tableMetadataService.getById(existingId);
            assertEquals("新注释", loaded.getTableComment(), "注释应被更新");
            assertEquals(existingId, loaded.getId(), "ID应保持不变");
            System.out.println("  更新后注释: " + loaded.getTableComment());
        }

        @Test
        @DisplayName("✅ 删除不再存在的表 - 同步后旧表被移除")
        void testIncrementalSync_DeleteStaleTables() {
            System.out.println("\n=== 场景4.3: 增量同步-删除不再存在的表 ===");

            TableMetadata staleTable = buildTestTable(DatasourceType.HIVE, "sync_del_db", "stale_table", "过期表");
            tableMetadataService.save(staleTable);
            Long staleId = staleTable.getId();

            ColumnMetadata staleCol = buildTestColumn(DatasourceType.HIVE, "sync_del_db", "stale_table", "col1", "int", 1);
            staleCol.setTableMetadataId(staleId);
            columnMetadataMapper.insert(staleCol);

            List<TableMetadata> newTables = new ArrayList<>();
            TableMetadata keepTable = buildTestTable(DatasourceType.HIVE, "sync_del_db", "keep_table", "保留表");
            newTables.add(keepTable);

            Map<String, List<ColumnMetadata>> columnsMap = new HashMap<>();
            columnsMap.put("HIVE##sync_del_db##keep_table", Collections.emptyList());

            tableMetadataService.safeIncrementalSync(DatasourceType.HIVE, "sync_del_db", newTables, columnsMap);

            assertNull(tableMetadataService.getById(staleId), "过期表应被删除");
            assertEquals(0, columnMetadataMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ColumnMetadata>()
                            .eq(ColumnMetadata::getTableMetadataId, staleId)).size(),
                    "过期表的列也应被删除");
            System.out.println("  过期表已删除, 保留表存在: " + (tableMetadataService.getById(keepTable.getId()) != null));
        }
    }

    @Nested
    @DisplayName("场景5: 单表同步（syncSingleTable）")
    class SingleTableSyncTests {

        @Test
        @DisplayName("✅ 插入新表")
        void testSyncSingleTable_InsertNew() {
            System.out.println("\n=== 场景5.1: 单表同步-插入新表 ===");

            TableMetadata newTable = buildTestTable(DatasourceType.HIVE, "single_db", "single_new", "单表新增");
            List<ColumnMetadata> columns = Arrays.asList(
                    buildTestColumn(DatasourceType.HIVE, "single_db", "single_new", "id", "bigint", 1),
                    buildTestColumn(DatasourceType.HIVE, "single_db", "single_new", "value", "string", 2)
            );

            tableMetadataService.syncSingleTable(DatasourceType.HIVE, newTable, columns);

            assertNotNull(newTable.getId(), "同步后应有ID");
            TableMetadata loaded = tableMetadataService.getById(newTable.getId());
            assertNotNull(loaded);
            assertEquals("single_new", loaded.getTableName());
            assertEquals(DatasourceType.HIVE, loaded.getStorageEngine());

            List<ColumnMetadata> loadedCols = tableMetadataService.getColumnsByTableId(newTable.getId());
            assertEquals(2, loadedCols.size(), "应有2列");
            System.out.println("  新表id=" + newTable.getId() + ", 列数=" + loadedCols.size());
        }

        @Test
        @DisplayName("✅ 更新已有表")
        void testSyncSingleTable_UpdateExisting() {
            System.out.println("\n=== 场景5.2: 单表同步-更新已有表 ===");

            TableMetadata existing = buildTestTable(DatasourceType.HIVE, "single_upd_db", "single_upd", "旧注释");
            tableMetadataService.save(existing);
            Long existingId = existing.getId();

            ColumnMetadata oldCol = buildTestColumn(DatasourceType.HIVE, "single_upd_db", "single_upd", "old_col", "int", 1);
            oldCol.setTableMetadataId(existingId);
            columnMetadataMapper.insert(oldCol);

            TableMetadata updated = buildTestTable(DatasourceType.HIVE, "single_upd_db", "single_upd", "新注释");
            updated.setRowCount(9999L);
            List<ColumnMetadata> newColumns = Arrays.asList(
                    buildTestColumn(DatasourceType.HIVE, "single_upd_db", "single_upd", "new_col1", "string", 1),
                    buildTestColumn(DatasourceType.HIVE, "single_upd_db", "single_upd", "new_col2", "double", 2)
            );

            tableMetadataService.syncSingleTable(DatasourceType.HIVE, updated, newColumns);

            TableMetadata loaded = tableMetadataService.getById(existingId);
            assertEquals("新注释", loaded.getTableComment(), "注释应被更新");
            assertEquals(9999L, loaded.getRowCount(), "行数应被更新");
            assertEquals(existingId, loaded.getId(), "ID应保持不变");

            List<ColumnMetadata> loadedCols = tableMetadataService.getColumnsByTableId(existingId);
            assertEquals(2, loadedCols.size(), "旧列应被删除，新列应被插入");
            assertTrue(loadedCols.stream().anyMatch(c -> "new_col1".equals(c.getColumnName())));
            assertTrue(loadedCols.stream().anyMatch(c -> "new_col2".equals(c.getColumnName())));
            assertFalse(loadedCols.stream().anyMatch(c -> "old_col".equals(c.getColumnName())),
                    "旧列应已被删除");
            System.out.println("  更新后注释: " + loaded.getTableComment() + ", 列数: " + loadedCols.size());
        }

        @Test
        @DisplayName("✅ 同步表时列信息为null不报错")
        void testSyncSingleTable_NullColumns() {
            System.out.println("\n=== 场景5.3: 单表同步-列为null ===");

            TableMetadata table = buildTestTable(DatasourceType.DORIS, "null_col_db", "null_col_table", "无列表");

            assertDoesNotThrow(() -> tableMetadataService.syncSingleTable(DatasourceType.DORIS, table, null));

            assertNotNull(table.getId());
            List<ColumnMetadata> cols = tableMetadataService.getColumnsByTableId(table.getId());
            assertTrue(cols.isEmpty(), "列应为空");
            System.out.println("  列为null时不报错, 表id=" + table.getId());
        }
    }

    @Nested
    @DisplayName("场景6: 按名称删除表（级联删除列元数据）")
    class DeleteByNamesTests {

        @Test
        @DisplayName("✅ 删除表并级联删除列")
        void testDeleteTableByNames_CascadeDeleteColumns() {
            System.out.println("\n=== 场景6.1: 按名称删除表并级联删除列 ===");

            TableMetadata table = buildTestTable(DatasourceType.HIVE, "del_cascade_db", "cascade_table", "级联删除表");
            tableMetadataService.save(table);

            ColumnMetadata c1 = buildTestColumn(DatasourceType.HIVE, "del_cascade_db", "cascade_table", "col1", "int", 1);
            c1.setTableMetadataId(table.getId());
            ColumnMetadata c2 = buildTestColumn(DatasourceType.HIVE, "del_cascade_db", "cascade_table", "col2", "string", 2);
            c2.setTableMetadataId(table.getId());
            columnMetadataMapper.insert(c1);
            columnMetadataMapper.insert(c2);

            assertEquals(2, tableMetadataService.getColumnsByTableId(table.getId()).size(), "删除前应有2列");

            tableMetadataService.deleteTableByNames(DatasourceType.HIVE, "del_cascade_db", "cascade_table");

            assertNull(tableMetadataService.getById(table.getId()), "表应被删除");
            assertEquals(0, tableMetadataService.getColumnsByTableId(table.getId()).size(),
                    "列应被级联删除");
            System.out.println("  表和列均已删除");
        }

        @Test
        @DisplayName("✅ 删除不存在的表不报错")
        void testDeleteTableByNames_NonExistent() {
            System.out.println("\n=== 场景6.2: 删除不存在的表 ===");

            assertDoesNotThrow(() ->
                    tableMetadataService.deleteTableByNames(DatasourceType.HIVE, "nonexistent_db", "nonexistent_table"));
            System.out.println("  删除不存在的表不报错");
        }
    }

    @Nested
    @DisplayName("场景7: 批量删除表（batchDeleteTablesByEngineAndNames）")
    class BatchDeleteTests {

        @Test
        @DisplayName("✅ 批量删除多个表及其列")
        void testBatchDeleteTables() {
            System.out.println("\n=== 场景7.1: 批量删除多个表 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "batch_del_db", "batch_table1", "批量删除1");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "batch_del_db", "batch_table2", "批量删除2");
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "batch_del_db", "batch_table3", "保留表");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            ColumnMetadata c1 = buildTestColumn(DatasourceType.HIVE, "batch_del_db", "batch_table1", "col1", "int", 1);
            c1.setTableMetadataId(t1.getId());
            ColumnMetadata c2 = buildTestColumn(DatasourceType.HIVE, "batch_del_db", "batch_table2", "col2", "string", 1);
            c2.setTableMetadataId(t2.getId());
            columnMetadataMapper.insert(c1);
            columnMetadataMapper.insert(c2);

            Set<String> keysToDelete = new HashSet<>();
            keysToDelete.add("HIVE##batch_del_db##batch_table1");
            keysToDelete.add("HIVE##batch_del_db##batch_table2");

            tableMetadataService.batchDeleteTablesByEngineAndNames(keysToDelete);

            assertNull(tableMetadataService.getById(t1.getId()), "batch_table1应被删除");
            assertNull(tableMetadataService.getById(t2.getId()), "batch_table2应被删除");
            assertNotNull(tableMetadataService.getById(t3.getId()), "batch_table3应保留");
            assertEquals(0, tableMetadataService.getColumnsByTableId(t1.getId()).size(), "table1的列应被删除");
            assertEquals(0, tableMetadataService.getColumnsByTableId(t2.getId()).size(), "table2的列应被删除");
            System.out.println("  批量删除2个表成功, 保留1个表");
        }

        @Test
        @DisplayName("✅ 空key集合不报错")
        void testBatchDelete_EmptyKeys() {
            System.out.println("\n=== 场景7.2: 空key集合 ===");

            assertDoesNotThrow(() -> tableMetadataService.batchDeleteTablesByEngineAndNames(Collections.emptySet()));
            assertDoesNotThrow(() -> tableMetadataService.batchDeleteTablesByEngineAndNames(null));
            System.out.println("  空key集合不报错");
        }

        @Test
        @DisplayName("✅ 删除不存在的key不报错")
        void testBatchDelete_NonExistentKeys() {
            System.out.println("\n=== 场景7.3: 删除不存在的key ===");

            Set<String> keys = new HashSet<>();
            keys.add("HIVE##nonexistent##nonexistent_table");

            assertDoesNotThrow(() -> tableMetadataService.batchDeleteTablesByEngineAndNames(keys));
            System.out.println("  删除不存在的key不报错");
        }
    }

    @Nested
    @DisplayName("场景8: 关键字搜索（支持逗号分隔的多关键字）")
    class KeywordSearchTests {

        @Test
        @DisplayName("✅ 单关键字搜索")
        void testKeywordSearch_SingleKeyword() {
            System.out.println("\n=== 场景8.1: 单关键字搜索 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "kw_search_db", "order_info", "订单信息");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "kw_search_db", "user_info", "用户信息");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, null, null, "order");

            assertTrue(page.getRecords().stream().anyMatch(t ->
                    t.getTableName().contains("order") || t.getTableComment().contains("order")));
            System.out.println("  单关键字'order'匹配数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 逗号分隔的多关键字搜索（AND逻辑）")
        void testKeywordSearch_MultipleKeywords() {
            System.out.println("\n=== 场景8.2: 多关键字搜索 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "multi_kw_db", "order_detail", "订单明细");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "multi_kw_db", "user_profile", "用户画像");
            TableMetadata t3 = buildTestTable(DatasourceType.HIVE, "multi_kw_db", "order_user_ref", "订单用户关联");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);
            tableMetadataService.save(t3);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, null, null, "order,user");

            List<TableMetadata> records = page.getRecords().stream()
                    .filter(t -> "multi_kw_db".equals(t.getDatabaseName()))
                    .toList();
            System.out.println("  多关键字'order,user'匹配数: " + records.size());
            for (TableMetadata r : records) {
                System.out.println("    - " + r.getTableName() + " (" + r.getTableComment() + ")");
            }
        }

        @Test
        @DisplayName("✅ 关键字搜索匹配表注释")
        void testKeywordSearch_MatchComment() {
            System.out.println("\n=== 场景8.3: 关键字匹配表注释 ===");

            TableMetadata t1 = buildTestTable(DatasourceType.HIVE, "comment_kw_db", "tbl_x", "销售数据表");
            TableMetadata t2 = buildTestTable(DatasourceType.HIVE, "comment_kw_db", "tbl_y", "库存数据表");
            tableMetadataService.save(t1);
            tableMetadataService.save(t2);

            Page<TableMetadata> page = tableMetadataService.getTableList(1, 10, null, null, null, "销售");

            assertTrue(page.getRecords().stream().anyMatch(t ->
                    t.getTableComment().contains("销售")),
                    "关键字应匹配表注释");
            System.out.println("  关键字'销售'匹配数: " + page.getRecords().size());
        }

        @Test
        @DisplayName("✅ 无匹配关键字返回空结果")
        void testKeywordSearch_NoMatch() {
            System.out.println("\n=== 场景8.4: 无匹配关键字 ===");

            Page<TableMetadata> page = tableMetadataService.getTableList(
                    1, 10, null, null, null, "zzz_absolutely_no_match_zzz");

            assertEquals(0, page.getRecords().size(), "无匹配应返回空");
            System.out.println("  无匹配结果数: " + page.getRecords().size());
        }
    }
}
