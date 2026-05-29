package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

public abstract class AbstractJdbcExecutionStrategy implements ExecutionStrategy {

    private static final int MAX_RESULT_ROWS = 1000;

    @Autowired
    protected DatasourceConfigService datasourceConfigService;

    @Autowired
    protected ProcessManager processManager;

    @Autowired
    protected MetadataSyncService metadataSyncService;

    @Autowired
    protected DataAccessPermissionService dataAccessPermissionService;

    @Autowired
    protected SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    protected SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    protected SysRoleMapper sysRoleMapper;

    protected abstract Logger getLog();

    protected abstract String getEngineName();

    protected abstract String getEngineCode();

    protected abstract void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception;

    protected abstract StorageEngineType resolveStorageEngineType(CodeExecutionContext context);

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
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
            getLog().info("{}执行SQL: {}", getEngineName(),
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

            List<String> sqlStatements = splitSqlStatements(sql);
            getLog().info("拆分出{}条SQL语句", sqlStatements.size());

            List<CodeExecutionResult.SqlResultItem> results = new ArrayList<>();
            int totalAffectedRows = 0;
            List<String> lastQueryColumns = null;
            List<List<String>> lastQueryRows = null;

            for (int i = 0; i < sqlStatements.size(); i++) {
                String singleSql = sqlStatements.get(i);
                getLog().info("执行第{}/{}条SQL: {}", i + 1, sqlStatements.size(),
                        singleSql.length() > 100 ? singleSql.substring(0, 100) + "..." : singleSql);

                try {
                    DdlInfo ddlInfo = detectDdlStatement(singleSql);
                    if (ddlInfo != null) {
                        if (!hasDdlPermission(context.getSubmitBy())) {
                            getLog().warn("用户[{}]无DDL执行权限, 拒绝执行: {}", context.getSubmitBy(), context.getSubmitByName());
                            results.add(CodeExecutionResult.SqlResultItem.failure(i, singleSql,
                                    "权限不足：只有超级管理员和运维人员才能执行DDL语句（CREATE/ALTER/DROP/TRUNCATE）"));
                            break;
                        }
                    } else {
                        checkDmlTablePermission(context, singleSql, executionId);
                    }

                    boolean hasResultSet = stmt.execute(singleSql);

                    if (ddlInfo != null) {
                        getLog().info("DDL语句执行成功, 开始同步元数据: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                        try {
                            StorageEngineType storageEngine = resolveStorageEngineType(context);
                            boolean syncSuccess = metadataSyncService.syncSingleTableMetadata(
                                    context.getPrimaryDatasourceConfig().getId(),
                                    storageEngine,
                                    ddlInfo.databaseName,
                                    ddlInfo.tableName,
                                    ddlInfo.ddlType
                            );
                            if (syncSuccess) {
                                getLog().info("元数据同步成功: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                            } else {
                                getLog().warn("元数据同步失败: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                            }
                        } catch (Exception syncEx) {
                            getLog().error("元数据同步异常: {}.{}", ddlInfo.databaseName, ddlInfo.tableName, syncEx);
                        }
                    }

                    if (hasResultSet) {
                        ResultSet rs = stmt.getResultSet();
                        List<String> columns = new ArrayList<>();
                        int colCount = rs.getMetaData().getColumnCount();
                        for (int c = 1; c <= colCount; c++) {
                            columns.add(rs.getMetaData().getColumnLabel(c));
                        }

                        List<List<String>> rows = new ArrayList<>();
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

                        results.add(CodeExecutionResult.SqlResultItem.querySuccess(i, singleSql, columns, rows, rowCount));
                        lastQueryColumns = columns;
                        lastQueryRows = rows;
                        totalAffectedRows += rowCount;
                    } else {
                        int affected = stmt.getUpdateCount();
                        results.add(CodeExecutionResult.SqlResultItem.dmlSuccess(i, singleSql, affected));
                        totalAffectedRows += affected;
                    }
                } catch (SecurityException e) {
                    results.add(CodeExecutionResult.SqlResultItem.failure(i, singleSql, e.getMessage()));
                    break;
                } catch (Exception e) {
                    getLog().error("第{}条SQL执行失败: {}", i + 1, e.getMessage());
                    results.add(CodeExecutionResult.SqlResultItem.failure(i, singleSql, e.getMessage()));
                    break;
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            boolean allSuccess = results.stream().allMatch(CodeExecutionResult.SqlResultItem::isSuccess);
            long successCount = results.stream().filter(CodeExecutionResult.SqlResultItem::isSuccess).count();

            CodeExecutionResult result;
            if (allSuccess) {
                String outputLog = sqlStatements.size() == 1
                        ? results.get(0).getMessage()
                        : "共" + sqlStatements.size() + "条SQL全部执行成功";
                result = CodeExecutionResult.success(executionId, outputLog,
                        lastQueryColumns, lastQueryRows, totalAffectedRows);
            } else {
                result = CodeExecutionResult.failure(executionId,
                        "第" + (results.size()) + "条SQL执行失败: " + results.get(results.size() - 1).getMessage());
                result.setColumns(lastQueryColumns);
                result.setRows(lastQueryRows);
                result.setAffectedRows(totalAffectedRows);
            }
            result.setResults(results);
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        } catch (Exception e) {
            getLog().error("{}执行失败", getEngineName(), e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, getEngineName() + "执行异常: " + e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
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

    protected List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        int length = sql.length();
        int i = 0;

        while (i < length) {
            char ch = sql.charAt(i);

            if (ch == '\'') {
                current.append(ch);
                i++;
                while (i < length) {
                    char inner = sql.charAt(i);
                    current.append(inner);
                    i++;
                    if (inner == '\'') {
                        if (i < length && sql.charAt(i) == '\'') {
                            current.append(sql.charAt(i));
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '"') {
                current.append(ch);
                i++;
                while (i < length) {
                    char inner = sql.charAt(i);
                    current.append(inner);
                    i++;
                    if (inner == '"') {
                        break;
                    }
                }
            } else if (ch == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (ch == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current = new StringBuilder();
                i++;
            } else {
                current.append(ch);
                i++;
            }
        }

        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }

        return statements;
    }

    protected void executeSetting(Statement stmt, String settingSql) throws Exception {
        try {
            stmt.execute(settingSql);
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

    protected static class DdlInfo {
        String ddlType;
        String databaseName;
        String tableName;
    }

    protected DdlInfo detectDdlStatement(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }

        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof CreateTable) {
                CreateTable createTable = (CreateTable) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "CREATE";
                info.tableName = createTable.getTable().getName();
                info.databaseName = createTable.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = createTable.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            if (stmt instanceof Alter) {
                Alter alter = (Alter) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "ALTER";
                info.tableName = alter.getTable().getName();
                info.databaseName = alter.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = alter.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            if (stmt instanceof Drop) {
                Drop drop = (Drop) stmt;
                if ("TABLE".equalsIgnoreCase(drop.getType())) {
                    DdlInfo info = new DdlInfo();
                    info.ddlType = "DROP";
                    info.tableName = drop.getName().getName();
                    info.databaseName = drop.getName().getSchemaName();
                    if (info.databaseName == null || info.databaseName.isEmpty()) {
                        info.databaseName = drop.getName().getDatabase().getDatabaseName();
                    }
                    return info;
                }
            }

            if (stmt instanceof Truncate) {
                Truncate truncate = (Truncate) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "TRUNCATE";
                info.tableName = truncate.getTable().getName();
                info.databaseName = truncate.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = truncate.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            return null;
        } catch (Exception e) {
            getLog().debug("SQL解析失败, 可能不是DDL语句: {}", e.getMessage());
            return null;
        }
    }

    protected boolean hasDdlPermission(Long userId) {
        if (userId == null) {
            return false;
        }

        try {
            Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
            List<Long> roleIds = tenantId != null ? sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId) : null;
            if (roleIds == null || roleIds.isEmpty()) {
                return false;
            }

            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            if (roles == null || roles.isEmpty()) {
                return false;
            }

            for (SysRole role : roles) {
                String roleCode = role.getRoleCode();
                if ("admin".equalsIgnoreCase(roleCode) || "ops".equalsIgnoreCase(roleCode)) {
                    getLog().debug("用户[{}]拥有DDL权限, 角色: {}", userId, role.getRoleName());
                    return true;
                }
            }

            getLog().debug("用户[{}]无DDL权限, 角色: {}", userId,
                    roles.stream().map(SysRole::getRoleName).reduce((a, b) -> a + "," + b).orElse("无"));
            return false;
        } catch (Exception e) {
            getLog().error("检查用户DDL权限异常: {}", userId, e);
            return false;
        }
    }

    protected void checkDmlTablePermission(CodeExecutionContext context, String sql, String executionId) {
        try {
            net.sf.jsqlparser.statement.Statement jsqlStmt = CCJSqlParserUtil.parse(sql);

            String operation = null;
            List<TableRef> tableRefs = new ArrayList<>();

            if (jsqlStmt instanceof Select) {
                operation = DataAccessPermissionService.TABLE_OP_SELECT;
                tableRefs = extractTablesFromSelect((Select) jsqlStmt);
            } else if (jsqlStmt instanceof Insert) {
                operation = DataAccessPermissionService.TABLE_OP_INSERT;
                Insert insert = (Insert) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = insert.getTable().getName();
                ref.databaseName = insert.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = insert.getTable().getDatabase() != null ?
                            insert.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            } else if (jsqlStmt instanceof Update) {
                operation = DataAccessPermissionService.TABLE_OP_UPDATE;
                Update update = (Update) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = update.getTable().getName();
                ref.databaseName = update.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = update.getTable().getDatabase() != null ?
                            update.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            } else if (jsqlStmt instanceof Delete) {
                operation = DataAccessPermissionService.TABLE_OP_DELETE;
                Delete delete = (Delete) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = delete.getTable().getName();
                ref.databaseName = delete.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = delete.getTable().getDatabase() != null ?
                            delete.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            }

            if (operation == null || tableRefs.isEmpty()) {
                getLog().debug("SQL语句不涉及表操作或无法解析: {}", sql.substring(0, Math.min(50, sql.length())));
                return;
            }

            String datasourceName = context.getPrimaryDatasourceConfig().getDatasourceName();
            String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

            for (TableRef tableRef : tableRefs) {
                String dbName = tableRef.databaseName != null ? tableRef.databaseName : defaultDatabase;

                boolean hasPermission = dataAccessPermissionService.hasTablePermission(
                        datasourceName,
                        dbName,
                        tableRef.tableName,
                        operation
                );

                if (!hasPermission) {
                    getLog().warn("用户[{}]无权对表 {}.{} 执行 {} 操作",
                            context.getSubmitBy(), dbName, tableRef.tableName, operation);
                    throw new SecurityException(String.format(
                            "权限不足：无权对表 %s.%s 执行 %s 操作",
                            dbName, tableRef.tableName, operation));
                }

                getLog().debug("用户[{}]拥有表 {}.{} 的 {} 权限",
                        context.getSubmitBy(), dbName, tableRef.tableName, operation);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            getLog().debug("DML权限检查解析失败, 放行执行: {}", e.getMessage());
        }
    }

    protected List<TableRef> extractTablesFromSelect(Select select) {
        List<TableRef> tableRefs = new ArrayList<>();

        try {
            Object selectBody = select.getSelectBody();

            if (selectBody.getClass().getName().contains("PlainSelect")) {
                java.lang.reflect.Method getFromItemMethod = selectBody.getClass().getMethod("getFromItem");
                Object fromItem = getFromItemMethod.invoke(selectBody);

                if (fromItem != null) {
                    extractTableFromItemDynamic(fromItem, tableRefs);
                }
            }
        } catch (Exception e) {
            getLog().debug("解析SELECT语句的FROM子句失败: {}", e.getMessage());
        }

        return tableRefs;
    }

    protected void extractTableFromItemDynamic(Object fromItem, List<TableRef> tableRefs) {
        try {
            String className = fromItem.getClass().getName();

            if (className.contains(".Table") && !className.contains("Join")) {
                TableRef ref = new TableRef();

                java.lang.reflect.Method getNameMethod = fromItem.getClass().getMethod("getName");
                ref.tableName = (String) getNameMethod.invoke(fromItem);

                try {
                    java.lang.reflect.Method getSchemaNameMethod = fromItem.getClass().getMethod("getSchemaName");
                    ref.databaseName = (String) getSchemaNameMethod.invoke(fromItem);

                    if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                        try {
                            java.lang.reflect.Method getTableMethod = fromItem.getClass().getMethod("getTable");
                            Object databaseObj = getTableMethod.invoke(fromItem);
                            if (databaseObj != null) {
                                java.lang.reflect.Method getDatabaseNameMethod = databaseObj.getClass().getMethod("getDatabaseName");
                                ref.databaseName = (String) getDatabaseNameMethod.invoke(databaseObj);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}

                tableRefs.add(ref);
            } else if (className.contains("SubJoin")) {
                java.lang.reflect.Method getLeftMethod = fromItem.getClass().getMethod("getLeft");
                Object left = getLeftMethod.invoke(fromItem);
                extractTableFromItemDynamic(left, tableRefs);

                java.lang.reflect.Method getJoinsMethod = fromItem.getClass().getMethod("getJoins");
                @SuppressWarnings("unchecked")
                List<Object> joins = (List<Object>) getJoinsMethod.invoke(fromItem);
                for (Object join : joins) {
                    java.lang.reflect.Method getRightItemMethod = join.getClass().getMethod("getRightItem");
                    Object rightItem = getRightItemMethod.invoke(join);
                    extractTableFromItemDynamic(rightItem, tableRefs);
                }
            } else if (className.contains("SubSelect")) {
                java.lang.reflect.Method getSelectBodyMethod = fromItem.getClass().getMethod("getSelectBody");
                Object subSelectBody = getSelectBodyMethod.invoke(fromItem);

                if (subSelectBody != null && subSelectBody.getClass().getName().contains("PlainSelect")) {
                    java.lang.reflect.Method getFromItemMethod = subSelectBody.getClass().getMethod("getFromItem");
                    Object subFromItem = getFromItemMethod.invoke(subSelectBody);
                    if (subFromItem != null) {
                        extractTableFromItemDynamic(subFromItem, tableRefs);
                    }
                }
            }
        } catch (Exception e) {
            getLog().debug("动态提取表信息失败: {}", e.getMessage());
        }
    }

    protected static class TableRef {
        String tableName;
        String databaseName;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
