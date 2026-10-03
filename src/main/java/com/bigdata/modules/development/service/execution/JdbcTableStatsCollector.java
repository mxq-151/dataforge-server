package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * <p>表清单只提供表名/注释等结构性信息，存储大小、分区列与压缩算法需要查询各引擎的
 * 系统表。本类按数据源的真实引擎分别取数，提供两种消费方式：</p>
 * <ul>
 *   <li>{@link #fill}：全量元数据同步时就地补齐到 {@link TableMetadata}（merge 只写非空字段，
 *       失败或不可得时不覆盖库中已有值）；</li>
 *   <li>{@link #loadSizeBytes}：仅取「表名 → 存储字节数」，供每日存储大小刷新任务使用，
 *       表名保留系统表返回的原始大小写。</li>
 * </ul>
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
            "SELECT TABLE_NAME, DATA_LENGTH, INDEX_LENGTH, CREATE_TIME, UPDATE_TIME, CREATE_OPTIONS "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    private static final String MYSQL_PARTITION_SQL =
            "SELECT TABLE_NAME, GROUP_CONCAT(DISTINCT PARTITION_EXPRESSION) AS EXPR "
                    + "FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = ? AND PARTITION_NAME IS NOT NULL AND PARTITION_EXPRESSION IS NOT NULL "
                    + "GROUP BY TABLE_NAME";

    private static final String POSTGRES_STATS_SQL =
            "SELECT c.relname AS table_name, pg_total_relation_size(c.oid) AS size_bytes "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r','p','m','f')";
    private static final String POSTGRES_PARTITION_SQL =
            "SELECT c.relname AS table_name, string_agg(a.attname, ',' ORDER BY a.attnum) AS partition_columns "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_partitioned_table pt ON pt.partrelid = c.oid "
                    + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(pt.partattrs) "
                    + "WHERE n.nspname = ? GROUP BY c.relname";

    private static final String DORIS_STATS_SQL =
            "SELECT TABLE_NAME, DATA_LENGTH, CREATE_TIME, UPDATE_TIME "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";

    private static final String CLICKHOUSE_STATS_SQL =
            "SELECT name, total_bytes, partition_key, metadata_modification_time "
                    + "FROM system.tables WHERE database = ?";
    private static final String CLICKHOUSE_CODEC_SQL =
            "SELECT table, arrayStringConcat(groupUniqArray(compression_codec), ',') AS codecs "
                    + "FROM system.columns WHERE database = ? GROUP BY table";

    private final Logger log;

    JdbcTableStatsCollector(Logger log) {
        this.log = log;
    }

    /** 单表系统表统计（字段可空，逐项合并） */
    static final class TableStats {
        Long sizeBytes;
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
     * 仅取「表名 → 存储字节数」（表名保留原始大小写），供每日存储大小刷新使用。
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