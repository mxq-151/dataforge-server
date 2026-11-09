package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 从源库元数据读取表清单中每张表的字段清单。
 *
 * <p>连接配置里不再有「字段列表」输入项，字段清单的唯一来源是：
 * 表清单（库.表）→ 源的 JDBC 元数据 → 去掉该表配置的排除字段。
 * 因此只有 JDBC 源（MySQL / PostgreSQL）可以作为源端。</p>
 *
 * <p>读取失败（连不上、表不存在、无权限）直接抛 400，把问题留在保存/提交阶段，
 * 而不是生成一个列清单为空的作业到集群上才失败。</p>
 */
public final class SourceColumnResolver {

    private static final int MYSQL_CONNECT_TIMEOUT_MS = 10_000;
    private static final int MYSQL_SOCKET_TIMEOUT_MS = 60_000;
    private static final int POSTGRES_TIMEOUT_SECONDS = 10;

    private SourceColumnResolver() {
    }

    /** 读取多张表的字段清单；返回 源表 → 列清单（保持入参顺序） */
    public static Map<String, List<ColumnDef>> resolveAll(DataSourceKind kind,
                                                          Map<String, Object> config,
                                                          List<TableSpec> tables) {
        if (!kind.isCdcSource()) {
            throw new BusinessException(400, "源端类型 " + (kind == null ? "" : kind.getLabel())
                    + " 无法自动读取字段清单：只有 CDC 源端（MySQL / PostgreSQL / Oracle / SQL Server / Db2 / OceanBase）支持按表清单从库表元数据取列");
        }
        SourceConnection.Endpoint endpoint = SourceConnection.endpoint(kind, config);
        String url = SourceConnection.jdbcUrl(kind, config);
        Map<String, List<ColumnDef>> result = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, properties(kind, config))) {
            DatabaseMetaData meta = connection.getMetaData();
            for (TableSpec table : tables) {
                result.put(table.getSourceTable(), loadColumns(kind, meta, endpoint, table));
            }
            return result;
        } catch (SQLException e) {
            throw new BusinessException(400, "连接源库读取字段清单失败：" + e.getMessage());
        }
    }

    private static Properties properties(DataSourceKind kind, Map<String, Object> config) {
        Properties props = new Properties();
        String username = str(config, "username");
        String password = str(config, "password");
        if (!username.isEmpty()) {
            props.setProperty("user", username);
        }
        if (!password.isEmpty()) {
            props.setProperty("password", password);
        }
        if (kind == DataSourceKind.MYSQL || kind == DataSourceKind.POSTGRESQL) {
            props.setProperty("connectTimeout", String.valueOf(kind == DataSourceKind.MYSQL
                    ? MYSQL_CONNECT_TIMEOUT_MS : POSTGRES_TIMEOUT_SECONDS));
            props.setProperty("socketTimeout", String.valueOf(kind == DataSourceKind.MYSQL
                    ? MYSQL_SOCKET_TIMEOUT_MS : POSTGRES_TIMEOUT_SECONDS * 6));
            if (kind == DataSourceKind.POSTGRESQL) {
                props.setProperty("loginTimeout", String.valueOf(POSTGRES_TIMEOUT_SECONDS));
            }
        }
        return props;
    }

    private static List<ColumnDef> loadColumns(DataSourceKind kind, DatabaseMetaData meta,
                                               SourceConnection.Endpoint endpoint, TableSpec table)
            throws SQLException {
        String catalog;
        String schema;
        switch (kind) {
            case MYSQL, OCEANBASE -> {
                catalog = firstNonEmpty(table.database(), endpoint.database());
                schema = null;
            }
            case POSTGRESQL -> {
                catalog = null;
                schema = endpoint.schema();
            }
            case ORACLE, DB2 -> {
                // 表清单前缀对 Oracle/Db2 不是库名；schema 统一取连接参数，Db2 允许表清单里写 schema.table
                String configured = kind == DataSourceKind.DB2 ? table.database() : "";
                catalog = null;
                schema = firstNonEmpty(configured, endpoint.schema()).toUpperCase(Locale.ROOT);
            }
            case SQLSERVER -> {
                catalog = endpoint.database();
                schema = firstNonEmpty(table.database(), endpoint.schema());
            }
            default -> {
                catalog = null;
                schema = null;
            }
        }
        List<OrdinalColumn> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table.table(), null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                String type = flinkType(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"));
                columns.add(new OrdinalColumn(rs.getInt("ORDINAL_POSITION"), new ColumnDef(name, type)));
            }
        }
        if (columns.isEmpty()) {
            throw new BusinessException(400, "表「" + table.getSourceTable()
                    + "」在源库中没有读到字段；请确认「库名.表名」是否正确、连接账号是否有访问权限");
        }
        columns.sort(Comparator.comparingInt(OrdinalColumn::ordinal));
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table.table())) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && !name.trim().isEmpty()) {
                    primaryKeys.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<ColumnDef> result = new ArrayList<>(columns.size());
        for (OrdinalColumn column : columns) {
            ColumnDef def = column.column();
            boolean pk = primaryKeys.contains(def.getName().toLowerCase(Locale.ROOT));
            result.add(pk ? new ColumnDef(def.getName(), def.getFlinkType(), true) : def);
        }
        return result;
    }

    private static String flinkType(int sqlType, String typeName, int size, int scale) {
        String vendor = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        if ("YEAR".equals(vendor)) {
            return "INT";
        }
        switch (sqlType) {
            case Types.BIT:
                return size > 1 ? "BYTES" : "BOOLEAN";
            case Types.BOOLEAN:
                return "BOOLEAN";
            case Types.TINYINT:
                return "TINYINT";
            case Types.SMALLINT:
                return "SMALLINT";
            case Types.INTEGER:
                return "INT";
            case Types.BIGINT:
                return "BIGINT";
            case Types.REAL:
            case Types.FLOAT:
                return "FLOAT";
            case Types.DOUBLE:
                return "DOUBLE";
            case Types.NUMERIC:
            case Types.DECIMAL: {
                int precision = size <= 0 ? 38 : Math.min(size, 38);
                int decimalDigits = Math.max(0, Math.min(scale, precision));
                return "DECIMAL(" + precision + "," + decimalDigits + ")";
            }
            case Types.DATE:
                return "DATE";
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return "TIME";
            case Types.TIMESTAMP:
                return "TIMESTAMP(3)";
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return "TIMESTAMP_LTZ(3)";
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return "BYTES";
            default:
                return "STRING";
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? second : first;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record OrdinalColumn(int ordinal, ColumnDef column) {
    }
}
