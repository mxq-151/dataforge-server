package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code storage_engine} 列持久化取值的回归测试。
 * <p>
 * 合并前该字段类型是 {@code StorageEngineType}：既没有 {@code @EnumValue} 也没有 {@code @JsonValue}，
 * MyBatis 按默认的 {@code EnumTypeHandler} 落库<b>枚举名</b>（{@code MYSQL}、{@code HIVE}）。
 * 合并后字段类型换成 {@link DatasourceType}，其 {@code code} 上带 {@code @EnumValue}，
 * MyBatis-Plus 改用 {@code MybatisEnumTypeHandler}，落库值变为<b>小写 code</b>（{@code mysql}、{@code hive}）。
 * </p>
 * <p>
 * 这条差异同时决定了「历史数据还能不能被读出来」，是本轮合并里风险最高的一项。
 * </p>
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("storage_engine 持久化取值回归（合并后由枚举名改为 code）")
class StorageEnginePersistenceRegressionTest {

    @Autowired
    private TableMetadataMapper tableMetadataMapper;
    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;
    @Autowired
    private TableMetadataService tableMetadataService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TableMetadata table(DatasourceType engine, String db, String tableName) {
        TableMetadata t = new TableMetadata();
        t.setDatasourceId(1L);
        t.setStorageEngine(engine);
        t.setDatabaseName(db);
        t.setTableName(tableName);
        t.setSyncTime(new Date());
        return t;
    }

    @Test
    @DisplayName("落库值为小写 code（改动前为大写枚举名 MYSQL）")
    void persistsLowercaseCode() {
        TableMetadata t = table(DatasourceType.MYSQL, "db_persist", "t_code");
        tableMetadataMapper.insert(t);

        String raw = jdbcTemplate.queryForObject(
                "select storage_engine from table_metadata where id = ?", String.class, t.getId());

        assertEquals("mysql", raw,
                "storage_engine 落库值由大写枚举名 'MYSQL' 变为小写 code 'mysql'");
    }

    @Test
    @DisplayName("写入后能按原枚举读回")
    void roundTrip() {
        TableMetadata t = table(DatasourceType.POSTGRESQL, "db_persist", "t_round");
        tableMetadataMapper.insert(t);

        TableMetadata loaded = tableMetadataMapper.selectById(t.getId());

        assertEquals(DatasourceType.POSTGRESQL, loaded.getStorageEngine());
    }

    @Test
    @DisplayName("按引擎过滤时，SQL 条件使用 code 而非枚举名")
    void queryUsesCode() {
        tableMetadataMapper.insert(table(DatasourceType.HIVE, "db_persist", "t_filter"));
        tableMetadataMapper.insert(table(DatasourceType.MYSQL, "db_persist", "t_filter"));

        List<TableMetadata> hiveRows = tableMetadataMapper.selectList(
                new LambdaQueryWrapper<TableMetadata>()
                        .eq(TableMetadata::getDatabaseName, "db_persist")
                        .eq(TableMetadata::getTableName, "t_filter")
                        .eq(TableMetadata::getStorageEngine, DatasourceType.HIVE));

        assertEquals(1, hiveRows.size(), "引擎过滤条件应命中且仅命中 hive 的那一行");
        assertEquals(DatasourceType.HIVE, hiveRows.get(0).getStorageEngine());
    }

    /**
     * 证明「必须先做数据迁移」：合并前落库的是枚举名，合并后按 code 解析，
     * 未经迁移的历史行会静默变成 null——不报错，但引擎过滤会整体失效。
     */
    @Test
    @DisplayName("迁移前：历史大写枚举名行被读成 null，引擎字段静默丢失")
    void legacyUppercaseRowsLostBeforeMigration() {
        LegacyRow row = insertLegacyRow("MYSQL");

        TableMetadata loaded;
        try {
            loaded = tableMetadataMapper.selectById(row.id);
        } catch (Exception e) {
            throw new AssertionError("历史数据（storage_engine='MYSQL'）读取异常: " + e.getMessage(), e);
        }

        assertNotNull(loaded, "历史行不应丢失");
        assertNull(loaded.getStorageEngine(),
                "预期未经迁移的历史行 storageEngine 为 null——这是本次合并的数据兼容性风险点，"
                        + "修复方式是执行一次 LOWER(storage_engine) 迁移");
    }

    @Test
    @DisplayName("迁移后：执行 LOWER(storage_engine) 后历史行可正确读出")
    void legacyUppercaseRowsReadableAfterMigration() {
        LegacyRow row = insertLegacyRow("MYSQL");

        int updated = jdbcTemplate.update(
                "update table_metadata set storage_engine = lower(storage_engine) where id = ?", row.id);
        assertEquals(1, updated);

        TableMetadata loaded = tableMetadataMapper.selectById(row.id);
        assertEquals(DatasourceType.MYSQL, loaded.getStorageEngine(),
                "迁移脚本执行后，历史行应能正确反序列化");
    }

    @Test
    @DisplayName("迁移后：历史行参与引擎过滤")
    void legacyRowsTakePartInEngineFilterAfterMigration() {
        LegacyRow row = insertLegacyRow("HIVE");
        jdbcTemplate.update("update table_metadata set storage_engine = lower(storage_engine) where id = ?", row.id);

        List<TableMetadata> hiveRows = tableMetadataMapper.selectList(
                new LambdaQueryWrapper<TableMetadata>()
                        .eq(TableMetadata::getDatabaseName, "db_legacy")
                        .eq(TableMetadata::getTableName, "t_legacy")
                        .eq(TableMetadata::getStorageEngine, DatasourceType.HIVE));

        assertEquals(1, hiveRows.size(), "迁移后的历史行应能被引擎过滤条件命中");
    }

    /** 模拟合并前写入的历史数据：storage_engine 落库的是枚举名 */
    private LegacyRow insertLegacyRow(String legacyEnumName) {
        jdbcTemplate.update("insert into table_metadata " +
                        "(datasource_id, storage_engine, database_name, table_name, sync_time) " +
                        "values (?, ?, ?, ?, now())",
                1L, legacyEnumName, "db_legacy", "t_legacy");

        Long id = jdbcTemplate.queryForObject(
                "select id from table_metadata where database_name = 'db_legacy' and table_name = 't_legacy'",
                Long.class);
        assertNotNull(id, "历史行插入失败");
        return new LegacyRow(id);
    }

    private record LegacyRow(Long id) {
    }

    @Test
    @DisplayName("列元数据的 storage_engine 落库值同样为 code")
    void columnPersistsCode() {
        TableMetadata t = table(DatasourceType.HBASE, "db_persist", "t_col");
        tableMetadataMapper.insert(t);

        ColumnMetadata col = new ColumnMetadata();
        col.setTableMetadataId(t.getId());
        col.setStorageEngine(DatasourceType.HBASE);
        col.setDatabaseName("db_persist");
        col.setTableName("t_col");
        col.setColumnName("rk");
        col.setColumnType("string");
        col.setOrdinalPosition(1);
        col.setSyncTime(new Date());
        columnMetadataMapper.insert(col);

        String raw = jdbcTemplate.queryForObject(
                "select storage_engine from column_metadata where id = ?", String.class, col.getId());

        assertEquals("hbase", raw);
    }

    @Test
    @DisplayName("getColumnsByNames 传入引擎时按 code 过滤，不同引擎同名表互不干扰")
    void columnsQueryIsolatedByEngine() {
        TableMetadata hiveTable = table(DatasourceType.HIVE, "db_iso", "orders");
        TableMetadata mysqlTable = table(DatasourceType.MYSQL, "db_iso", "orders");
        tableMetadataMapper.insert(hiveTable);
        tableMetadataMapper.insert(mysqlTable);

        ColumnMetadata hiveCol = new ColumnMetadata();
        hiveCol.setTableMetadataId(hiveTable.getId());
        hiveCol.setStorageEngine(DatasourceType.HIVE);
        hiveCol.setDatabaseName("db_iso");
        hiveCol.setTableName("orders");
        hiveCol.setColumnName("hive_only");
        hiveCol.setColumnType("string");
        hiveCol.setOrdinalPosition(1);
        hiveCol.setSyncTime(new Date());
        columnMetadataMapper.insert(hiveCol);

        ColumnMetadata mysqlCol = new ColumnMetadata();
        mysqlCol.setTableMetadataId(mysqlTable.getId());
        mysqlCol.setStorageEngine(DatasourceType.MYSQL);
        mysqlCol.setDatabaseName("db_iso");
        mysqlCol.setTableName("orders");
        mysqlCol.setColumnName("mysql_only");
        mysqlCol.setColumnType("string");
        mysqlCol.setOrdinalPosition(1);
        mysqlCol.setSyncTime(new Date());
        columnMetadataMapper.insert(mysqlCol);

        List<ColumnMetadata> hiveCols =
                tableMetadataService.getColumnsByNames(DatasourceType.HIVE, "db_iso", "orders");
        List<ColumnMetadata> mysqlCols =
                tableMetadataService.getColumnsByNames(DatasourceType.MYSQL, "db_iso", "orders");

        assertEquals(1, hiveCols.size());
        assertEquals("hive_only", hiveCols.get(0).getColumnName());
        assertEquals(1, mysqlCols.size());
        assertEquals("mysql_only", mysqlCols.get(0).getColumnName());
    }

    @Test
    @DisplayName("不传引擎时跨引擎返回全部同名列（保持改动前行为）")
    void columnsQueryWithoutEngineReturnsAll() {
        TableMetadata hiveTable = table(DatasourceType.HIVE, "db_all", "orders");
        TableMetadata mysqlTable = table(DatasourceType.MYSQL, "db_all", "orders");
        tableMetadataMapper.insert(hiveTable);
        tableMetadataMapper.insert(mysqlTable);

        for (DatasourceType engine : List.of(DatasourceType.HIVE, DatasourceType.MYSQL)) {
            ColumnMetadata col = new ColumnMetadata();
            col.setTableMetadataId(engine == DatasourceType.HIVE ? hiveTable.getId() : mysqlTable.getId());
            col.setStorageEngine(engine);
            col.setDatabaseName("db_all");
            col.setTableName("orders");
            col.setColumnName("col_" + engine.getCode());
            col.setColumnType("string");
            col.setOrdinalPosition(1);
            col.setSyncTime(new Date());
            columnMetadataMapper.insert(col);
        }

        List<ColumnMetadata> all = tableMetadataService.getColumnsByNames(null, "db_all", "orders");

        assertEquals(2, all.size(), "未指定引擎时应返回两个引擎下的列");
        assertTrue(all.stream().anyMatch(c -> "col_hive".equals(c.getColumnName())));
        assertTrue(all.stream().anyMatch(c -> "col_mysql".equals(c.getColumnName())));
    }

    @Test
    @DisplayName("storage_engine 为 null 的记录可正常入库与读出")
    void nullStorageEngineRoundTrip() {
        TableMetadata t = table(null, "db_null", "t_null");
        tableMetadataMapper.insert(t);

        TableMetadata loaded = tableMetadataMapper.selectById(t.getId());
        assertNull(loaded.getStorageEngine());
    }
}
