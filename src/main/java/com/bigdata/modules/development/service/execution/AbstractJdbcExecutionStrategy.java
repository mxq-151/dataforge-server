package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

                    getLog().info("📊 查询结果详情: executionId={}, 列数={}, 行数={}",
                            executionId, colCount, rowCount);
                    executionLogListener.publishLog(executionId, "[结果] 查询返回 " + rowCount + " 行, " + colCount + " 列 (" +
                            String.join(", ", columns) + ")");
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
            try (ResultSet rs = metaData.getCatalogs()) {
                while (rs.next()) {
                    databases.add(rs.getString("TABLE_CAT"));
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
            try (ResultSet rs = metaData.getTables(databaseName, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    TableMetadata tableMeta = new TableMetadata();
                    tableMeta.setTableName(rs.getString("TABLE_NAME"));
                    tableMeta.setTableType(rs.getString("TABLE_TYPE"));
                    tableMeta.setTableComment(rs.getString("REMARKS"));
                    tableMeta.setDatabaseName(databaseName);
                    tables.add(tableMeta);
                }
            }
        } catch (Exception e) {
            getLog().warn("获取表列表失败[database={}]: {}", databaseName, e.getMessage());
        } finally {
            closeQuietly(connection);
        }
        return tables;
    }

    @Override
    public List<ColumnMetadataVO> listColumns(DatasourceConfig datasourceConfig, String databaseName, String tableName) {
        List<ColumnMetadataVO> columns = new ArrayList<>();
        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceConfig.getId(), databaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            int position = 1;
            try (ResultSet rs = metaData.getColumns(databaseName, null, tableName, "%")) {
                while (rs.next()) {
                    ColumnMetadataVO col = new ColumnMetadataVO();
                    col.setColumnName(rs.getString("COLUMN_NAME"));
                    col.setColumnType(rs.getString("TYPE_NAME"));
                    col.setColumnComment(rs.getString("REMARKS"));
                    col.setOrdinalPosition(position++);
                    col.setNullable("YES".equals(rs.getString("IS_NULLABLE")) ? 1 : 0);
                    col.setDatabaseName(databaseName);
                    col.setTableName(tableName);
                    columns.add(col);
                }
            }
            // 补充主键信息
            try (ResultSet pkRs = metaData.getPrimaryKeys(databaseName, null, tableName)) {
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
}
