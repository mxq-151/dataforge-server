package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC 系引擎的表统计信息采集。
 *
 * <p>表清单只提供表名/注释等结构性信息，存储大小、行数、分区列与压缩算法需要查询各引擎的
 * 系统表。本类按数据源的真实引擎分别取数，提供三种消费方式：</p>
 * <ul>
 *   <li>{@link #fill}：全量元数据同步时就地补齐到 {@link TableMetadata}（merge 只写非空字段，
 *       失败或不可得时不覆盖库中已有值）；</li>
 *   <li>{@link #loadSizeBytes}：仅取「表名 → 存储字节数」，供每日存储统计刷新任务使用，
 *       表名保留系统表返回的原始大小写；</li>
 *   <li>{@link #loadRowCounts}：仅取「表名 → 行数」，与大小同源同口径（行数的 0 是合法值）。</li>
 * </ul>
 *
 * <h3>分区表口径</h3>
 * <p>PostgreSQL 的分区父表自身不存数据：体积与行数都必须累加其分区子表，否则会恒为
 * 0 / 未知（见 {@code POSTGRES_STATS_SQL} 的注释）。子表本身不作为独立表返回。</p>
 *
 * <h3>失败策略</h3>
 * <p>统计信息属于增量信息：单个查询失败只记 warn 并保留空值，既不阻断调用方，
 * 也不清空已有数据。各引擎的 SQL 差异集中在本类，执行策略侧只负责调用。</p>
 *
 * @author dataforge
 */
final class JdbcTableStatsCollector {

    /** MySQL CREATE_OPTIONS 中的压缩声明，如 COMPRESSION="zlib" */
    private static final Pattern MYSQL_COMPRESSION =
            Pattern.compile("(?i)COMPRESSION\\s*=\\s*\"?(\\w+)\"?");

    private static final String MYSQL_STATS_SQL =
            "SELECT TABLE_NAME, DATA_LENGTH, INDEX_LENGTH, TABLE_ROWS, CREATE_TIME, UPDATE_TIME, CREATE_OPTIONS "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    private static final String MYSQL_PARTITION_SQL =
            "SELECT TABLE_NAME, GROUP_CONCAT(DISTINCT PARTITION_EXPRESSION) AS EXPR "
                    + "FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = ? AND PARTITION_NAME IS NOT NULL AND PARTITION_EXPRESSION IS NOT NULL "
                    + "GROUP BY TABLE_NAME";

    /**
     * PostgreSQL 表统计。
     *
     * <p>两处必须是「父表口径」，否则体积与行数会整表丢失：</p>
     * <ol>
     *   <li>分区父表（{@code relkind='p'}）自身不存数据，{@code pg_total_relation_size(父)} 恒为 0，
     *       必须 {@code sum} 其分区子表；</li>
     *   <li>行数取 {@code pg_class.reltuples}（ANALYZE/autovacuum 维护的估算值），
     *       {@code -1} 表示从未统计过、按「未知」处理；分区父表的 reltuples 常年为 -1，
     *       同样改为累加子表。</li>
     * </ol>
     * <p>分区子表本身不返回（{@code NOT c.relispartition}）：同步侧已把子表排除出元数据，
     * 返回它们只会产生匹配不上的噪声键。</p>
     */
    private static final String POSTGRES_STATS_SQL =
            "SELECT c.relname AS table_name, "
                    + "(pg_total_relation_size(c.oid) + COALESCE(("
                    + "SELECT sum(pg_total_relation_size(ch.oid)) FROM pg_inherits i "
                    + "JOIN pg_class ch ON ch.oid = i.inhrelid WHERE i.inhparent = c.oid), 0)) AS size_bytes, "
                    + "CASE WHEN c.reltuples >= 0 THEN c.reltuples::bigint ELSE ("
                    + "SELECT sum(ch.reltuples)::bigint FROM pg_inherits i "
                    + "JOIN pg_class ch ON ch.oid = i.inhrelid "
                    + "WHERE i.inhparent = c.oid AND ch.reltuples >= 0) END AS row_count "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r','p','m','f') AND NOT c.relispartition";
    private static final String POSTGRES_PARTITION_SQL =
            "SELECT c.relname AS table_name, string_agg(a.attname, ',' ORDER BY a.attnum) AS partition_columns "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_partitioned_table pt ON pt.partrelid = c.oid "
                    + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(pt.partattrs) "
                    + "WHERE n.nspname = ? GROUP BY c.relname";

    private static final String DORIS_STATS_SQL =
            "SELECT TABLE_NAME, DATA_LENGTH, TABLE_ROWS, CREATE_TIME, UPDATE_TIME "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";

    private static final String CLICKHOUSE_STATS_SQL =
            "SELECT name, total_bytes, total_rows, partition_key, metadata_modification_time "
                    + "FROM system.tables WHERE database = ?";
    private static final String CLICKHOUSE_CODEC_SQL =
            "SELECT table, arrayStringConcat(groupUniqArray(compression_codec), ',') AS codecs "
                    + "FROM system.columns WHERE database = ? GROUP BY table";

    /** MySQL 分区明细：按 TABLE_NAME + PARTITION_ORDINAL_POSITION 取每个表的最大分区 */
    private static final String MYSQL_PARTITION_SIZE_SQL =
            "SELECT TABLE_NAME, PARTITION_NAME, PARTITION_DESCRIPTION, PARTITION_ORDINAL_POSITION, "
                    + "DATA_LENGTH, INDEX_LENGTH FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = ? AND PARTITION_NAME IS NOT NULL";

    /** PostgreSQL 分区子表：父表 relname 对应已纳管表，分区值从 relpartbound 解析 */
    private static final String POSTGRES_PARTITION_SIZE_SQL =
            "SELECT p.relname AS parent_table, ch.relname AS partition_name, "
                    + "pg_total_relation_size(ch.oid) AS size_bytes, "
                    + "pg_get_expr(ch.relpartbound, ch.oid) AS bound "
                    + "FROM pg_class p JOIN pg_namespace n ON n.oid = p.relnamespace "
                    + "JOIN pg_inherits i ON i.inhparent = p.oid "
                    + "JOIN pg_class ch ON ch.oid = i.inhrelid "
                    + "WHERE n.nspname = ?";

    /** ClickHouse 活跃分区大小 */
    private static final String CLICKHOUSE_PARTITION_SIZE_SQL =
            "SELECT table, partition, sum(bytes_on_disk) AS size_bytes FROM system.parts "
                    + "WHERE database = ? AND active GROUP BY table, partition";

    /** PG 分区边界表达式中的取值：FOR VALUES FROM ('2026-10-09') TO (...) → 2026-10-09 */
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']+)'");

    private final Logger log;

    JdbcTableStatsCollector(Logger log) {
        this.log = log;
    }

    /** 单表系统表统计（字段可空，逐项合并） */
    static final class TableStats {
        Long sizeBytes;
        /** 行数估算值；null 表示上游未提供或从未统计过（不可与 0 混淆） */
        Long rowCount;
        Date tableCreateTime;
        Date lastModifyTime;
        String partitionColumns;
        String compression;
    }

    /**
     * 按引擎补齐表统计信息。
     *
     * @param engine       数据源的真实引擎（注意：执行策略是共用的 JDBC 策略，不能按策略类型判断）
     * @param connection   已建立的库级连接，由调用方管理生命周期
     * @param databaseName 当前库
     * @param tables       本次 listTables 的结果，就地补齐
     */
    void fill(DatasourceType engine, Connection connection, String databaseName, List<TableMetadata> tables) {
        if (engine == null || connection == null || databaseName == null
                || tables == null || tables.isEmpty()) {
            return;
        }
        Map<String, TableStats> stats = load(engine, connection, databaseName);
        if (stats.isEmpty()) {
            return;
        }
        Map<String, TableMetadata> byName = indexByLowerName(tables);
        for (Map.Entry<String, TableStats> entry : stats.entrySet()) {
            TableMetadata table = byName.get(lower(entry.getKey()));
            if (table != null) {
                apply(table, entry.getValue());
            }
        }
    }

    /**
     * 仅取「表名 → 存储字节数」（表名保留原始大小写），供每日存储统计刷新使用。
     * 取不到大小的表不出现在结果中。
     */
    Map<String, Long> loadSizeBytes(DatasourceType engine, Connection connection, String databaseName) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        if (engine == null || connection == null || databaseName == null) {
            return sizes;
        }
        for (Map.Entry<String, TableStats> entry : load(engine, connection, databaseName).entrySet()) {
            Long size = entry.getValue().sizeBytes;
            if (size != null && size > 0) {
                sizes.put(entry.getKey(), size);
            }
        }
        return sizes;
    }

    /**
     * 仅取「表名 → 行数」（表名保留原始大小写），与 {@link #loadSizeBytes} 同源同口径。
     *
     * <p>与大小不同，行数 {@code 0} 是有意义的值（表确实是空的），因此这里保留 0、
     * 只过滤「未知」（null）。</p>
     */
    Map<String, Long> loadRowCounts(DatasourceType engine, Connection connection, String databaseName) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (engine == null || connection == null || databaseName == null) {
            return counts;
        }
        for (Map.Entry<String, TableStats> entry : load(engine, connection, databaseName).entrySet()) {
            Long count = entry.getValue().rowCount;
            if (count != null && count >= 0) {
                counts.put(entry.getKey(), count);
            }
        }
        return counts;
    }

    /**
     * 采集「表名 → 当日最新分区大小」。
     *
     * <p>JDBC 系各引擎按真实引擎分支：MySQL 查 {@code information_schema.PARTITIONS}、
     * PostgreSQL 查 {@code pg_inherits} 子表、ClickHouse 聚合 {@code system.parts}、
     * Doris/StarRocks 逐表 {@code SHOW PARTITIONS}。只保留请求清单里的表；取不到大小的表
     * 不出现在结果中。单个表/查询失败只记 warn 并跳过，不影响其余。</p>
     *
     * @param tableNames 本次要采集的表名（同一库），键按调用方传入的原名返回
     */
    Map<String, ExecutionStrategy.TablePartitionSize> loadLatestPartitions(
            DatasourceType engine, Connection connection, String databaseName, Collection<String> tableNames) {
        Map<String, ExecutionStrategy.TablePartitionSize> result = new LinkedHashMap<>();
        if (engine == null || connection == null || databaseName == null
                || tableNames == null || tableNames.isEmpty()) {
            return result;
        }
        Map<String, String> wantedByLower = new HashMap<>();
        for (String name : tableNames) {
            if (name != null && !name.isBlank()) {
                wantedByLower.put(lower(name), name);
            }
        }
        if (wantedByLower.isEmpty()) {
            return result;
        }
        try {
            switch (engine) {
                case MYSQL -> queryMySqlPartitions(connection, databaseName, wantedByLower, result);
                case POSTGRESQL -> queryPostgresPartitions(connection, databaseName, wantedByLower, result);
                case CLICKHOUSE -> queryClickHousePartitions(connection, databaseName, wantedByLower, result);
                case DORIS, STARROCKS -> queryDorisPartitions(connection, databaseName, wantedByLower, result);
                default -> {
                    // 无分区大小的低成本口径
                }
            }
        } catch (Exception e) {
            log.warn("采集分区大小失败[engine={}, database={}]: {}", engine, databaseName, e.getMessage());
        }
        return result;
    }

    private Map<String, TableStats> load(DatasourceType engine, Connection connection, String databaseName) {
        try {
            return switch (engine) {
                case MYSQL -> queryMySql(connection, databaseName);
                case POSTGRESQL -> queryPostgres(connection, databaseName);
                case DORIS, STARROCKS -> queryDoris(connection, databaseName);
                case CLICKHOUSE -> queryClickHouse(connection, databaseName);
                default -> Map.of();
            };
        } catch (Exception e) {
            log.warn("采集表统计信息失败[engine={}, database={}]: {}", engine, databaseName, e.getMessage());
            return Map.of();
        }
    }

    private static void apply(TableMetadata table, TableStats stats) {
        if (stats.sizeBytes != null && stats.sizeBytes > 0) {
            table.setSizeBytes(stats.sizeBytes);
        }
        if (stats.rowCount != null) {
            table.setRowCount(stats.rowCount);
        }
        if (stats.tableCreateTime != null) {
            table.setTableCreateTime(stats.tableCreateTime);
        }
        if (stats.lastModifyTime != null) {
            table.setLastModifyTime(stats.lastModifyTime);
        }
        if (stats.partitionColumns != null) {
            table.setPartitionColumns(stats.partitionColumns);
        }
        if (stats.compression != null) {
            table.setCompression(stats.compression);
        }
    }

    // ==================================================================
    // MySQL / MariaDB 语法族
    // ==================================================================

    private Map<String, TableStats> queryMySql(Connection connection, String databaseName) {
        Map<String, TableStats> stats = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(MYSQL_STATS_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name == null) {
                        continue;
                    }
                    TableStats row = new TableStats();
                    long size = zeroIfNull(nullableLong(rs, "DATA_LENGTH"))
                            + zeroIfNull(nullableLong(rs, "INDEX_LENGTH"));
                    row.sizeBytes = size > 0 ? size : null;
                    row.rowCount = mysqlRowCount(rs);
                    row.tableCreateTime = rs.getTimestamp("CREATE_TIME");
                    row.lastModifyTime = rs.getTimestamp("UPDATE_TIME");
                    row.compression = mysqlCompression(rs.getString("CREATE_OPTIONS"));
                    stats.put(name, row);
                }
            }
        } catch (Exception e) {
            log.warn("采集MySQL表统计失败[database={}]: {}", databaseName, e.getMessage());
        }

        // 分区列：PARTITION_EXPRESSION 即分区表达式（如 `dt` / to_days(`created_at`)）
        try (PreparedStatement ps = connection.prepareStatement(MYSQL_PARTITION_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    TableStats row = name == null ? null : stats.get(name);
                    String expr = cleanExpression(rs.getString("EXPR"));
                    if (row != null && expr != null) {
                        row.partitionColumns = expr;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("采集MySQL分区信息失败[database={}]: {}", databaseName, e.getMessage());
        }
        return stats;
    }

    /** MySQL 系 TABLE_ROWS 是 InnoDB 的估算值、可为 NULL；非负才采纳 */
    private static Long mysqlRowCount(ResultSet rs) throws SQLException {
        Long rows = nullableLong(rs, "TABLE_ROWS");
        return rows != null && rows >= 0 ? rows : null;
    }

    /** CREATE_OPTIONS 中的压缩算法（MySQL 无独立压缩列，仅显式声明时可取） */
    private static String mysqlCompression(String createOptions) {
        if (createOptions == null || createOptions.isBlank()) {
            return null;
        }
        Matcher matcher = MYSQL_COMPRESSION.matcher(createOptions);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        if (createOptions.toUpperCase(Locale.ROOT).contains("COMPRESSED")) {
            return "COMPRESSED";
        }
        return null;
    }

    // ==================================================================
    // PostgreSQL
    // ==================================================================

    private Map<String, TableStats> queryPostgres(Connection connection, String databaseName) {
        Map<String, TableStats> stats = new LinkedHashMap<>();
        // total_relation_size 含索引与 TOAST；PostgreSQL 无建表/修改时间系统列
        try (PreparedStatement ps = connection.prepareStatement(POSTGRES_STATS_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    if (name == null) {
                        continue;
                    }
                    TableStats row = new TableStats();
                    Long size = nullableLong(rs, "size_bytes");
                    row.sizeBytes = size != null && size > 0 ? size : null;
                    Long rows = nullableLong(rs, "row_count");
                    row.rowCount = rows != null && rows >= 0 ? rows : null;
                    stats.put(name, row);
                }
            }
        } catch (Exception e) {
            log.warn("采集PostgreSQL表统计失败[database={}]: {}", databaseName, e.getMessage());
        }

        try (PreparedStatement ps = connection.prepareStatement(POSTGRES_PARTITION_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    TableStats row = name == null ? null : stats.get(name);
                    String columns = cleanExpression(rs.getString("partition_columns"));
                    if (row != null && columns != null) {
                        row.partitionColumns = columns;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("采集PostgreSQL分区信息失败[database={}]: {}", databaseName, e.getMessage());
        }
        return stats;
    }

    // ==================================================================
    // Doris / StarRocks（information_schema 对齐 MySQL 口径）
    // ==================================================================

    private Map<String, TableStats> queryDoris(Connection connection, String databaseName) {
        Map<String, TableStats> stats = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(DORIS_STATS_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name == null) {
                        continue;
                    }
                    TableStats row = new TableStats();
                    Long size = nullableLong(rs, "DATA_LENGTH");
                    row.sizeBytes = size != null && size > 0 ? size : null;
                    row.rowCount = mysqlRowCount(rs);
                    row.tableCreateTime = rs.getTimestamp("CREATE_TIME");
                    row.lastModifyTime = rs.getTimestamp("UPDATE_TIME");
                    stats.put(name, row);
                }
            }
        } catch (Exception e) {
            log.warn("采集Doris/StarRocks表统计失败[database={}]: {}", databaseName, e.getMessage());
        }
        // 分区列与压缩算法在 information_schema 不可得，依赖 DDL 路径（建表时解析落库）
        return stats;
    }

    // ==================================================================
    // ClickHouse
    // ==================================================================

    private Map<String, TableStats> queryClickHouse(Connection connection, String databaseName) {
        Map<String, TableStats> stats = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(CLICKHOUSE_STATS_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name == null) {
                        continue;
                    }
                    TableStats row = new TableStats();
                    Long size = nullableLong(rs, "total_bytes");
                    row.sizeBytes = size != null && size > 0 ? size : null;
                    Long rows = nullableLong(rs, "total_rows");
                    row.rowCount = rows != null && rows >= 0 ? rows : null;
                    row.lastModifyTime = rs.getTimestamp("metadata_modification_time");
                    row.partitionColumns = cleanExpression(rs.getString("partition_key"));
                    stats.put(name, row);
                }
            }
        } catch (Exception e) {
            log.warn("采集ClickHouse表统计失败[database={}]: {}", databaseName, e.getMessage());
        }

        try (PreparedStatement ps = connection.prepareStatement(CLICKHOUSE_CODEC_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table");
                    TableStats row = name == null ? null : stats.get(name);
                    String codecs = cleanExpression(rs.getString("codecs"));
                    if (row != null && codecs != null) {
                        row.compression = codecs;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("采集ClickHouse压缩算法失败[database={}]: {}", databaseName, e.getMessage());
        }
        return stats;
    }

    // ==================================================================
    // 分区大小（各引擎「当日最新分区」）
    // ==================================================================

    /** MySQL / MariaDB：PARTITION_ORDINAL_POSITION 最大的即最新分区 */
    private void queryMySqlPartitions(Connection connection, String databaseName,
                                      Map<String, String> wantedByLower,
                                      Map<String, ExecutionStrategy.TablePartitionSize> result) {
        try (PreparedStatement ps = connection.prepareStatement(MYSQL_PARTITION_SIZE_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Long> bestOrdinal = new HashMap<>();
                while (rs.next()) {
                    String requested = wantedByLower.get(lower(rs.getString("TABLE_NAME")));
                    if (requested == null) {
                        continue;
                    }
                    long ordinal = zeroIfNull(nullableLong(rs, "PARTITION_ORDINAL_POSITION"));
                    Long seen = bestOrdinal.get(requested);
                    if (seen != null && ordinal < seen) {
                        continue;
                    }
                    long size = zeroIfNull(nullableLong(rs, "DATA_LENGTH"))
                            + zeroIfNull(nullableLong(rs, "INDEX_LENGTH"));
                    if (size <= 0) {
                        continue;
                    }
                    String value = cleanExpression(rs.getString("PARTITION_DESCRIPTION"));
                    if (value == null) {
                        value = cleanExpression(rs.getString("PARTITION_NAME"));
                    }
                    bestOrdinal.put(requested, ordinal);
                    result.put(requested, new ExecutionStrategy.TablePartitionSize(value, size));
                }
            }
        } catch (Exception e) {
            log.warn("采集MySQL分区大小失败[database={}]: {}", databaseName, e.getMessage());
        }
    }

    /** PostgreSQL：分区子表按分区值取最大；值从 relpartbound 解析，取不到用子表名兜底 */
    private void queryPostgresPartitions(Connection connection, String databaseName,
                                         Map<String, String> wantedByLower,
                                         Map<String, ExecutionStrategy.TablePartitionSize> result) {
        try (PreparedStatement ps = connection.prepareStatement(POSTGRES_PARTITION_SIZE_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> bestKey = new HashMap<>();
                while (rs.next()) {
                    String requested = wantedByLower.get(lower(rs.getString("parent_table")));
                    if (requested == null) {
                        continue;
                    }
                    Long size = nullableLong(rs, "size_bytes");
                    if (size == null || size <= 0) {
                        continue;
                    }
                    String value = extractPartitionValue(rs.getString("bound"),
                            cleanExpression(rs.getString("partition_name")));
                    String key = value == null ? "" : value;
                    String seen = bestKey.get(requested);
                    if (seen != null && key.compareTo(seen) <= 0) {
                        continue;
                    }
                    bestKey.put(requested, key);
                    result.put(requested, new ExecutionStrategy.TablePartitionSize(value, size));
                }
            }
        } catch (Exception e) {
            log.warn("采集PostgreSQL分区大小失败[database={}]: {}", databaseName, e.getMessage());
        }
    }

    /** ClickHouse：按 partition 聚合活跃分区字节，取分区键最大者 */
    private void queryClickHousePartitions(Connection connection, String databaseName,
                                           Map<String, String> wantedByLower,
                                           Map<String, ExecutionStrategy.TablePartitionSize> result) {
        try (PreparedStatement ps = connection.prepareStatement(CLICKHOUSE_PARTITION_SIZE_SQL)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> bestKey = new HashMap<>();
                while (rs.next()) {
                    String requested = wantedByLower.get(lower(rs.getString("table")));
                    if (requested == null) {
                        continue;
                    }
                    Long size = nullableLong(rs, "size_bytes");
                    if (size == null || size <= 0) {
                        continue;
                    }
                    String partition = cleanExpression(rs.getString("partition"));
                    String key = partition == null ? "" : partition;
                    String seen = bestKey.get(requested);
                    if (seen != null && key.compareTo(seen) <= 0) {
                        continue;
                    }
                    bestKey.put(requested, key);
                    result.put(requested, new ExecutionStrategy.TablePartitionSize(partition, size));
                }
            }
        } catch (Exception e) {
            log.warn("采集ClickHouse分区大小失败[database={}]: {}", databaseName, e.getMessage());
        }
    }

    /**
     * Doris / StarRocks：逐表 {@code SHOW PARTITIONS}，取最后一行有大小者（按创建顺序，最新在最后）。
     * <p>版本差异导致结果列名不统一：按列标签含 {@code partitionname} / {@code size} 定位；
     * 找不到大小列即跳过该表，不猜列序。</p>
     */
    private void queryDorisPartitions(Connection connection, String databaseName,
                                      Map<String, String> wantedByLower,
                                      Map<String, ExecutionStrategy.TablePartitionSize> result) {
        for (String requested : wantedByLower.values()) {
            String sql = "SHOW PARTITIONS FROM `" + databaseName.replace("`", "") + "`.`"
                    + requested.replace("`", "") + "`";
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                ExecutionStrategy.TablePartitionSize latest = lastPartitionRow(rs);
                if (latest != null) {
                    result.put(requested, latest);
                }
            } catch (Exception e) {
                log.warn("采集Doris分区大小失败[{}.{}]: {}", databaseName, requested, e.getMessage());
            }
        }
    }

    /** 取结果集最后一条有效的「分区名 + 大小」；列名不识别时返回 null */
    private static ExecutionStrategy.TablePartitionSize lastPartitionRow(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int nameIndex = -1;
        int sizeIndex = -1;
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            if (nameIndex < 0 && label.contains("partitionname")) {
                nameIndex = i;
            }
            if (sizeIndex < 0 && label.contains("size")) {
                sizeIndex = i;
            }
        }
        if (nameIndex < 0 || sizeIndex < 0) {
            return null;
        }
        ExecutionStrategy.TablePartitionSize latest = null;
        while (rs.next()) {
            long size = rs.getLong(sizeIndex);
            if (rs.wasNull() || size <= 0) {
                continue;
            }
            String name = rs.getString(nameIndex);
            latest = new ExecutionStrategy.TablePartitionSize(
                    name == null ? null : name.trim(), size);
        }
        return latest;
    }

    /** 从 PG 分区边界表达式取值；解析不到用子表名兜底 */
    private static String extractPartitionValue(String bound, String fallbackName) {
        if (bound != null) {
            Matcher matcher = QUOTED_VALUE.matcher(bound);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return fallbackName;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Map<String, TableMetadata> indexByLowerName(List<TableMetadata> tables) {
        Map<String, TableMetadata> byName = new HashMap<>();
        for (TableMetadata table : tables) {
            if (table != null && table.getTableName() != null) {
                byName.put(lower(table.getTableName()), table);
            }
        }
        return byName;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    /** 去掉反引号/引号并压缩空白，空串按 null 处理 */
    private static String cleanExpression(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace("`", "").replace("\"", "")
                .replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}