package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JDBC类型执行策略的抽象基类。
 * <p>
 * 继承 {@link AbstractBaseExecutionStrategy}，复用统一的权限校验、行级过滤和数据脱敏能力，
 * 同时封装了通用的JDBC执行流程（连接管理、SQL执行、结果集处理等）。
 * </p>
 */
public abstract class AbstractJdbcExecutionStrategy extends AbstractBaseExecutionStrategy {

    private static final int MAX_RESULT_ROWS = 1000;

    @Autowired
    protected DatasourceConfigService datasourceConfigService;

    @Autowired
    protected ProcessManager processManager;

    protected abstract String getEngineName();

    protected abstract String getEngineCode();

    protected abstract void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception;

    protected abstract DatasourceType resolveStorageEngineType(CodeExecutionContext context);

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = context.getExecutionId();
        getLog().info("🔄 [{}] 策略开始执行, executionId={}", getEngineName(), executionId);
        long startTime = System.currentTimeMillis();

        if (context.getPrimaryDatasourceConfig() == null) {
            return CodeExecutionResult.failure(executionId, getEngineName() + "执行引擎需要指定数据源");
        }

        Connection connection = null;
        Statement stmt = null;
        String registeredId = null;
        try {
            connection = datasourceConfigService.getConnection(
                    context.getPrimaryDatasourceConfig().getId(),
                    context.getPrimaryDatasourceDatabaseName());
            stmt = connection.createStatement();
            registeredId = processManager.registerStatement(stmt, context.getSubmitBy(), getEngineCode());
            applySessionSettings(stmt, context);

            String sql = context.getCodeContent();
            if (sql == null || sql.trim().isEmpty()) {
                return CodeExecutionResult.failure(executionId, "SQL语句不能为空");
            }
            sql = sql.trim();
            getLog().info("{}执行SQL: {}", getEngineName(),
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

            executionLogListener.publishLog(executionId, "[" + getEngineName() + "] 开始执行SQL");

            int affectedRows = 0;
            List<String> columns = null;
            List<List<String>> rows = null;

            try {
                // DDL检测（用于后续元数据同步，权限检查已在checkPermission中完成）
                boolean hasResultSet = stmt.execute(sql);
                getLog().info("📊 SQL执行结果: hasResultSet={}, SQL={}",
                        hasResultSet, sql.length() > 80 ? sql.substring(0, 80) + "..." : sql);

                // DDL元数据同步（调用基类方法）
                handleDdlMetadataSync(sql, context, executionId);

                if (hasResultSet) {
                    ResultSet rs = stmt.getResultSet();
                    columns = new ArrayList<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int c = 1; c <= colCount; c++) {
                        columns.add(rs.getMetaData().getColumnLabel(c));
                    }

                    rows = new ArrayList<>();
                    int rowCount = 0;
                    while (rs.next() && rowCount < MAX_RESULT_ROWS) {
                        List<String> row = new ArrayList<>();
                        for (int c = 1; c <= colCount; c++) {
                            String val = rs.getString(c);
                            row.add(val != null ? val : "NULL");
                        }
                        rows.add(row);
                        rowCount++;
                    }
                    affectedRows += rowCount;

                    // 行级过滤 + 数据脱敏（使用基类提供的方法）
                    applyRowFilterAndMasking(rows, columns, sql, context);

                    getLog().info("📊 查询结果详情: executionId={}, 列数={}, 行数={}(过滤后), 原始SQL读取={}",
                            executionId, columns == null ? 0 : columns.size(), rows == null ? 0 : rows.size(), rowCount);
                    executionLogListener.publishLog(executionId, "[结果] 查询返回 " + (rows == null ? 0 : rows.size()) + " 行, " +
                            (columns == null ? 0 : columns.size()) + " 列 (" + String.join(", ", columns) + ")");
                } else {
                    int affected = stmt.getUpdateCount();
                    affectedRows += affected;
                    executionLogListener.publishLog(executionId, "[结果] 影响行数: " + affected);
                }
            } catch (SecurityException e) {
                getLog().error("SQL执行权限异常: {}", e.getMessage());
                executionLogListener.publishLog(executionId, "[错误] " + e.getMessage());
                long duration = System.currentTimeMillis() - startTime;
                CodeExecutionResult failResult = CodeExecutionResult.failure(executionId, e.getMessage());
                failResult.setStartTime(new Date(startTime));
                failResult.setEndTime(new Date());
                failResult.setDurationMs((int) duration);
                return failResult;
            } catch (Exception e) {
                getLog().error("SQL执行失败: {}", e.getMessage());
                executionLogListener.publishLog(executionId, "[错误] " + e.getMessage());
                long duration = System.currentTimeMillis() - startTime;
                CodeExecutionResult failResult = CodeExecutionResult.failure(executionId, e.getMessage());
                failResult.setColumns(columns);
                failResult.setRows(rows);
                failResult.setAffectedRows(affectedRows);
                failResult.setStartTime(new Date(startTime));
                failResult.setEndTime(new Date());
                failResult.setDurationMs((int) duration);
                return failResult;
            }

            long duration = System.currentTimeMillis() - startTime;

            CodeExecutionResult result = CodeExecutionResult.success(executionId, "执行成功",
                    columns, rows, affectedRows);
            getLog().info("📊 构建成功结果: executionId={}, columns={}, rows={}, affectedRows={}",
                    executionId,
                    columns != null ? columns.size() : 0,
                    rows != null ? rows.size() : 0,
                    affectedRows);
            executionLogListener.publishLog(executionId, "[完成] 执行成功, 耗时" + duration + "ms");
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        } catch (Exception e) {
            getLog().error("{}执行失败", getEngineName(), e);
            long duration = System.currentTimeMillis() - startTime;
            String errMsg = getEngineName() + "执行异常: " + e.getMessage();
            executionLogListener.publishLog(executionId, "[异常] " + errMsg);
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, errMsg);
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        } finally {
            if (registeredId != null) {
                processManager.unregister(registeredId);
            }
            closeQuietly(stmt);
            closeQuietly(connection);
        }
    }

    /**
     * 对查询结果统一应用行级过滤和数据脱敏（调用基类方法）
     */
    protected void executeSetting(Statement stmt, String settingSql) throws Exception {
        try {
            stmt.execute(settingSql);
            while (true) {
                ResultSet rs = stmt.getResultSet();
                if (rs != null) {
                    rs.close();
                }
                if ((stmt.getMoreResults() == false) && (stmt.getUpdateCount() == -1)) {
                    break;
                }
            }
            getLog().debug("应用{}会话设置: {}", getEngineName(), settingSql);
        } catch (Exception e) {
            getLog().warn("应用{}会话设置失败: {}, 错误: {}", getEngineName(), settingSql, e.getMessage());
        }
    }

    protected void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 数据源元数据查询方法 ====================

    @Override
    public boolean testConnection(DatasourceConfig datasourceConfig) {
        Connection connection = null;
        try {
            String dbName = (datasourceConfig.getDatabaseName() != null && !datasourceConfig.getDatabaseName().isEmpty())
                    ? datasourceConfig.getDatabaseName().get(0) : null;
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), dbName);
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            getLog().warn("测试数据源连接失败: {}", e.getMessage());
            return false;
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public List<String> listDatabases(DatasourceConfig datasourceConfig) {
        List<String> databases = new ArrayList<>();
        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), null);
            DatabaseMetaData metaData = connection.getMetaData();
            // PostgreSQL 中 databaseName 语义为 schema：列出当前库下的 schema，
            // 才能与 listColumns/listTables 的 schema 口径保持一致（否则会列出其他库并误删元数据）
            boolean postgres = isPostgres(datasourceConfig);
            try (ResultSet rs = postgres ? metaData.getSchemas() : metaData.getCatalogs()) {
                String column = postgres ? "TABLE_SCHEM" : "TABLE_CAT";
                while (rs.next()) {
                    databases.add(rs.getString(column));
                }
            }
        } catch (Exception e) {
            getLog().warn("获取数据库列表失败: {}", e.getMessage());
        } finally {
            closeQuietly(connection);
        }
        return databases;
    }

    @Override
    public List<TableMetadata> listTables(DatasourceConfig datasourceConfig, String databaseName) {
        List<TableMetadata> tables = new ArrayList<>();
        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), databaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            // PostgreSQL 的 databaseName 是 schema，需放在 schemaPattern 位置而非 catalog
            boolean postgres = isPostgres(datasourceConfig);
            String catalog = postgres ? null : databaseName;
            String schemaPattern = postgres ? databaseName : null;
            // PostgreSQL 的分区父表(relkind='p')在 JDBC 里是独立的 "PARTITIONED TABLE" 类型：
            // 不显式请求时只会返回 *_default 这类分区子表，而表/列注释都挂在父表上，
            // 导致同步后库表列表里全是无注释的分区壳表。
            String[] tableTypes = postgres
                    ? new String[]{"TABLE", "VIEW", "PARTITIONED TABLE"}
                    : new String[]{"TABLE", "VIEW"};
            try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", tableTypes)) {
                while (rs.next()) {
                    TableMetadata tableMeta = new TableMetadata();
                    tableMeta.setTableName(rs.getString("TABLE_NAME"));
                    tableMeta.setTableType(rs.getString("TABLE_TYPE"));
                    tableMeta.setTableComment(rs.getString("REMARKS"));
                    tableMeta.setDatabaseName(databaseName);
                    tables.add(tableMeta);
                }
            }
            if (postgres) {
                // 分区子表不进元数据：只保留分区父表
                removePostgresPartitionChildren(connection, databaseName, tables);
            }
            // 系统表口径补齐：存储大小/建表修改时间/分区列/压缩算法（按真实引擎分支，失败仅告警）
            new JdbcTableStatsCollector(getLog()).fill(
                    datasourceConfig.getDatasourceType(), connection, databaseName, tables);
        } catch (Exception e) {
            getLog().warn("获取表列表失败[database={}]: {}", databaseName, e.getMessage());
        } finally {
            closeQuietly(connection);
        }
        return tables;
    }

    /**
     * 剔除 PostgreSQL 的分区子表（{@code pg_class.relispartition = true}）。
     * <p>分区子表（含 {@code *_default} 默认分区）只是物理存储单元：注释、分区口径与
     * 结构定义都挂在分区父表上，数据探索/资产目录只应展示父表，否则同一张逻辑表会出现
     * 两份、且分区壳表没有任何注释。查询失败时保留原表清单，避免误删元数据。</p>
     */
    private void removePostgresPartitionChildren(Connection connection, String schemaName, List<TableMetadata> tables) {
        if (tables.isEmpty()) {
            return;
        }
        Set<String> children = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relispartition")) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null) {
                        children.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("查询PostgreSQL分区子表失败[schema={}]: {}", schemaName, e.getMessage());
            return;
        }
        if (children.isEmpty()) {
            return;
        }
        tables.removeIf(t -> t.getTableName() != null
                && children.contains(t.getTableName().toLowerCase(Locale.ROOT)));
    }

    /**
     * 采集「表名 → 存储字节数」：复用系统表统计口径，供每日存储大小刷新任务使用。
     * 失败时返回空 Map（调用方覆盖写，不影响存量值）。
     */
    @Override
    public Map<String, Long> listTableSizes(DatasourceConfig datasourceConfig, String databaseName) {
        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), databaseName);
            return new JdbcTableStatsCollector(getLog())
                    .loadSizeBytes(datasourceConfig.getDatasourceType(), connection, databaseName);
        } catch (Exception e) {
            getLog().warn("采集表存储大小失败[database={}]: {}", databaseName, e.getMessage());
            return Map.of();
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public List<ColumnMetadataVO> listColumns(DatasourceConfig datasourceConfig, String databaseName, String tableName) {
        List<ColumnMetadataVO> columns = new ArrayList<>();
        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), databaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            // PostgreSQL 的 databaseName 是 schema，需放在 schemaPattern 位置而非 catalog
            boolean postgres = isPostgres(datasourceConfig);
            String catalog = postgres ? null : databaseName;
            String schemaPattern = postgres ? databaseName : null;
            int position = 1;
            try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, tableName, "%")) {
                while (rs.next()) {
                    ColumnMetadataVO col = new ColumnMetadataVO();
                    col.setColumnName(rs.getString("COLUMN_NAME"));
                    col.setColumnType(rs.getString("TYPE_NAME"));
                    col.setColumnComment(rs.getString("REMARKS"));
                    col.setOrdinalPosition(position++);
                    col.setNullable("YES".equals(rs.getString("IS_NULLABLE")) ? 1 : 0);
                    // 默认值（驱动对无默认值返回 null；个别驱动不提供该列时由外层 catch 兜底）
                    col.setDefaultValue(rs.getString("COLUMN_DEF"));
                    col.setDatabaseName(databaseName);
                    col.setTableName(tableName);
                    columns.add(col);
                }
            }
            // 补充主键信息
            try (ResultSet pkRs = metaData.getPrimaryKeys(catalog, schemaPattern, tableName)) {
                while (pkRs.next()) {
                    String pkColName = pkRs.getString("COLUMN_NAME");
                    for (ColumnMetadataVO col : columns) {
                        if (pkColName.equals(col.getColumnName())) {
                            col.setIsPrimaryKey(1);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("获取字段信息失败[{}.{}]: {}", databaseName, tableName, e.getMessage());
        } finally {
            closeQuietly(connection);
        }
        return columns;
    }

    /**
     * 判断数据源真实引擎是否为 PostgreSQL。
     * <p>执行策略是共用的 JDBC 策略（PG 走 DorisExecutionStrategy），
     * 因此必须按数据源真实类型判断，不能按策略类型判断。</p>
     */
    protected boolean isPostgres(DatasourceConfig datasourceConfig) {
        return datasourceConfig != null && datasourceConfig.getDatasourceType() == DatasourceType.POSTGRESQL;
    }
}
