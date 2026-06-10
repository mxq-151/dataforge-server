package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import com.bigdata.modules.system.entity.SysUser;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
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

    @Autowired
    protected SysUserMapper sysUserMapper;

    @Autowired
    protected ExecutionLogListener executionLogListener;

    protected abstract Logger getLog();

    protected abstract String getEngineName();

    protected abstract String getEngineCode();

    protected abstract void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception;

    protected abstract StorageEngineType resolveStorageEngineType(CodeExecutionContext context);

    @Override
    public void checkPermission(CodeExecutionContext context) {
        String sql = context.getCodeContent();
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        sql = sql.trim();

        // DDL权限检查
        DdlInfo ddlInfo = detectDdlStatement(sql);
        if (ddlInfo != null) {
            if (!hasDdlPermission(context.getSubmitBy())) {
                getLog().warn("用户[{}]无DDL执行权限, 拒绝执行: {}", context.getSubmitBy(), context.getSubmitByName());
                throw new SecurityException("权限不足：只有超级管理员和运维人员才能执行DDL语句（CREATE/ALTER/DROP/TRUNCATE）");
            }
            return;
        }

        // DML表级权限检查
        checkDmlTablePermission(context, sql, null);
    }

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
                // 权限检查已移至 checkPermission()，在异步提交前的请求线程中执行
                // 此处仅保留DDL检测用于后续元数据同步
                DdlInfo ddlInfo = detectDdlStatement(sql);

                boolean hasResultSet = stmt.execute(sql);
                getLog().info("📊 SQL执行结果: hasResultSet={}, SQL={}",
                        hasResultSet, sql.length() > 80 ? sql.substring(0, 80) + "..." : sql);

                if (ddlInfo != null) {
                    String ddlMsg = "DDL语句执行成功: " + ddlInfo.ddlType + " " +
                            (ddlInfo.databaseName != null ? ddlInfo.databaseName + "." : "") + ddlInfo.tableName;
                    getLog().info(ddlMsg + ", 开始同步元数据");
                    executionLogListener.publishLog(executionId, "[DDL] " + ddlMsg);
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
                            executionLogListener.publishLog(executionId, "[元数据] 同步成功: " + ddlInfo.tableName);
                        } else {
                            getLog().warn("元数据同步失败: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                            executionLogListener.publishLog(executionId, "[元数据] 同步失败: " + ddlInfo.tableName);
                        }
                    } catch (Exception syncEx) {
                        getLog().error("元数据同步异常: {}.{}", ddlInfo.databaseName, ddlInfo.tableName, syncEx);
                        executionLogListener.publishLog(executionId, "[元数据] 同步异常: " + syncEx.getMessage());
                    }
                }

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

                    String datasourceName = context.getPrimaryDatasourceConfig().getDatasourceName();
                    String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

                    SelectPermissionInfo permInfo = extractSelectPermissionInfo(sql, datasourceName, defaultDatabase);
                    if (permInfo != null && permInfo.tableRef != null) {
                        String dbName = permInfo.tableRef.databaseName != null ? permInfo.tableRef.databaseName : defaultDatabase;
                        SysTablePermission mergedPerm = dataAccessPermissionService.getMergedTablePermission(
                                datasourceName, dbName, permInfo.tableRef.tableName);
                        if (mergedPerm != null) {
                            if (mergedPerm.getRowFilterSql() != null && !mergedPerm.getRowFilterSql().isBlank()) {
                                int beforeSize = rows.size();
                                rows = applyRowFilter(rows, columns, mergedPerm.getRowFilterSql());
                                rowCount = rows.size();
                                getLog().info("行级过滤: {} -> {} 行, 过滤条件: {}", beforeSize, rowCount, mergedPerm.getRowFilterSql());
                            }
                            if (mergedPerm.getDataMaskingRule() != null && !mergedPerm.getDataMaskingRule().isBlank()) {
                                applyDataMasking(rows, columns, mergedPerm.getDataMaskingRule());
                                getLog().info("已应用数据脱敏规则, 表: {}.{}", dbName, permInfo.tableRef.tableName);
                            }
                        }
                    }

                    getLog().info("📊 查询结果详情: executionId={}, 列数={}, 行数={}, 列名={}",
                            executionId, colCount, rowCount, columns);
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
                failResult.setStartTime(new java.util.Date(startTime));
                failResult.setEndTime(new java.util.Date());
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
                failResult.setStartTime(new java.util.Date(startTime));
                failResult.setEndTime(new java.util.Date());
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
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        } catch (Exception e) {
            getLog().error("{}执行失败", getEngineName(), e);
            long duration = System.currentTimeMillis() - startTime;
            String errMsg = getEngineName() + "执行异常: " + e.getMessage();
            executionLogListener.publishLog(executionId, "[异常] " + errMsg);
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, errMsg);
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

    protected void executeSetting(Statement stmt, String settingSql) throws Exception {
        try {
            stmt.execute(settingSql);
            // 消费所有残留结果
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
            // 平台超级管理员直接拥有DDL权限
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null && Integer.valueOf(1).equals(user.getIsAdmin())) {
                getLog().debug("用户[{}]是平台超级管理员, 直接拥有DDL权限", userId);
                return true;
            }

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
            // 平台超级管理员跳过表权限检查（数据库查询，不依赖SecurityContext）
            if (hasDdlPermission(context.getSubmitBy())) {
                return;
            }

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

    protected static class SelectPermissionInfo {
        TableRef tableRef;
        String operation;
    }

    protected SelectPermissionInfo extractSelectPermissionInfo(String sql, String datasourceName, String defaultDatabase) {
        try {
            net.sf.jsqlparser.statement.Statement jsqlStmt = CCJSqlParserUtil.parse(sql);
            if (!(jsqlStmt instanceof Select)) {
                return null;
            }
            SelectPermissionInfo info = new SelectPermissionInfo();
            info.operation = DataAccessPermissionService.TABLE_OP_SELECT;
            List<TableRef> tableRefs = extractTablesFromSelect((Select) jsqlStmt);
            if (!tableRefs.isEmpty()) {
                info.tableRef = tableRefs.get(0);
            }
            return info;
        } catch (Exception e) {
            getLog().debug("解析SELECT权限信息失败: {}", e.getMessage());
            return null;
        }
    }

    protected List<List<String>> applyRowFilter(List<List<String>> rows, List<String> columns, String rowFilterSql) {
        if (rows == null || rows.isEmpty() || rowFilterSql == null || rowFilterSql.isBlank()) {
            return rows;
        }
        try {
            String filterExpr = rowFilterSql.trim();
            String filterLower = filterExpr.toLowerCase();
            if (!filterLower.startsWith("where")) {
                filterExpr = "WHERE " + filterExpr;
            }
            java.util.Map<String, Integer> colIndexMap = new java.util.LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colIndexMap.put(columns.get(i).toLowerCase(), i);
            }
            String finalFilterExpr = filterExpr;
            return rows.stream().filter(row -> {
                try {
                    return evaluateRowFilterExpression(row, columns, colIndexMap, finalFilterExpr);
                } catch (Exception e) {
                    getLog().debug("行过滤表达式求值失败，保留行: {}", e.getMessage());
                    return true;
                }
            }).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            getLog().warn("行级过滤失败，返回原始结果: {}", e.getMessage());
            return rows;
        }
    }

    protected boolean evaluateRowFilterExpression(List<String> row, List<String> columns, java.util.Map<String, Integer> colIndexMap, String filterSql) {
        String expr = filterSql;
        if (expr.toLowerCase().startsWith("where")) {
            expr = expr.substring(5).trim();
        }
        try {
            Expression whereExpr = CCJSqlParserUtil.parseCondExpression(expr);
            return evaluateExpression(whereExpr, row, colIndexMap);
        } catch (Exception e) {
            getLog().debug("行过滤SQL解析失败，保留行: {}", e.getMessage());
            return true;
        }
    }

    protected boolean evaluateExpression(Expression expr, List<String> row, java.util.Map<String, Integer> colIndexMap) {
        if (expr == null) {
            return true;
        }

        if (expr instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
            AndExpression andExpr = (AndExpression) expr;
            return evaluateExpression(andExpr.getLeftExpression(), row, colIndexMap)
                    && evaluateExpression(andExpr.getRightExpression(), row, colIndexMap);
        }

        if (expr instanceof OrExpression) {
            OrExpression orExpr = (OrExpression) expr;
            return evaluateExpression(orExpr.getLeftExpression(), row, colIndexMap)
                    || evaluateExpression(orExpr.getRightExpression(), row, colIndexMap);
        }

        if (expr instanceof Parenthesis) {
            Parenthesis paren = (Parenthesis) expr;
            return evaluateExpression(paren.getExpression(), row, colIndexMap);
        }

        if (expr instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) expr;
            return compareColumnValue(eq.getLeftExpression(), eq.getRightExpression(), "=", row, colIndexMap);
        }

        if (expr instanceof NotEqualsTo) {
            NotEqualsTo neq = (NotEqualsTo) expr;
            return compareColumnValue(neq.getLeftExpression(), neq.getRightExpression(), "!=", row, colIndexMap);
        }

        if (expr instanceof GreaterThan) {
            GreaterThan gt = (GreaterThan) expr;
            return compareColumnValue(gt.getLeftExpression(), gt.getRightExpression(), ">", row, colIndexMap);
        }

        if (expr instanceof MinorThan) {
            MinorThan lt = (MinorThan) expr;
            return compareColumnValue(lt.getLeftExpression(), lt.getRightExpression(), "<", row, colIndexMap);
        }

        if (expr instanceof GreaterThanEquals) {
            GreaterThanEquals gte = (GreaterThanEquals) expr;
            return compareColumnValue(gte.getLeftExpression(), gte.getRightExpression(), ">=", row, colIndexMap);
        }

        if (expr instanceof MinorThanEquals) {
            MinorThanEquals lte = (MinorThanEquals) expr;
            return compareColumnValue(lte.getLeftExpression(), lte.getRightExpression(), "<=", row, colIndexMap);
        }

        if (expr instanceof LikeExpression) {
            LikeExpression like = (LikeExpression) expr;
            return compareColumnValue(like.getLeftExpression(), like.getRightExpression(), "LIKE", row, colIndexMap);
        }

        if (expr instanceof InExpression) {
            InExpression inExpr = (InExpression) expr;
            Expression left = inExpr.getLeftExpression();
            Expression rightItems = inExpr.getRightExpression();
            if (left instanceof Column && rightItems instanceof net.sf.jsqlparser.expression.operators.relational.ExpressionList) {
                String colName = ((Column) left).getColumnName();
                Integer colIdx = colIndexMap.get(colName.toLowerCase());
                if (colIdx == null || colIdx >= row.size()) {
                    return true;
                }
                String cellValue = row.get(colIdx);
                if ("NULL".equals(cellValue)) cellValue = null;

                List<Expression> items = ((net.sf.jsqlparser.expression.operators.relational.ExpressionList) rightItems).getExpressions();
                for (Expression item : items) {
                    String valStr = extractStringValue(item);
                    if (valStr != null && valStr.equals(cellValue)) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }

        getLog().debug("未支持的表达式类型: {}, 保留行", expr.getClass().getSimpleName());
        return true;
    }

    private boolean compareColumnValue(Expression leftExpr, Expression rightExpr, String operator,
                                       List<String> row, java.util.Map<String, Integer> colIndexMap) {
        String colName = null;
        String valueStr = null;

        if (leftExpr instanceof Column && !(rightExpr instanceof Column)) {
            colName = ((Column) leftExpr).getColumnName();
            valueStr = extractStringValue(rightExpr);
        } else if (rightExpr instanceof Column && !(leftExpr instanceof Column)) {
            colName = ((Column) rightExpr).getColumnName();
            valueStr = extractStringValue(leftExpr);
            operator = reverseOperator(operator);
        } else {
            getLog().debug("比较表达式中缺少列引用或两侧都是列: {} {} {}", leftExpr, operator, rightExpr);
            return true;
        }

        if (colName == null || valueStr == null) {
            return true;
        }

        Integer colIdx = colIndexMap.get(colName.toLowerCase());
        if (colIdx == null || colIdx >= row.size()) {
            return true;
        }

        String cellValue = row.get(colIdx);
        if ("NULL".equals(cellValue)) cellValue = null;

        return doCompare(cellValue, operator, valueStr);
    }

    private String extractStringValue(Expression expr) {
        if (expr instanceof StringValue) {
            return ((StringValue) expr).getValue();
        }
        if (expr instanceof net.sf.jsqlparser.expression.LongValue) {
            return String.valueOf(((net.sf.jsqlparser.expression.LongValue) expr).getValue());
        }
        if (expr instanceof net.sf.jsqlparser.expression.DoubleValue) {
            return String.valueOf(((net.sf.jsqlparser.expression.DoubleValue) expr).getValue());
        }
        if (expr instanceof net.sf.jsqlparser.expression.StringValue) {
            return ((net.sf.jsqlparser.expression.StringValue) expr).getValue();
        }
        if (expr instanceof net.sf.jsqlparser.expression.NullValue) {
            return null;
        }
        String strVal = expr.toString();
        if (strVal.startsWith("'") && strVal.endsWith("'")) {
            return strVal.substring(1, strVal.length() - 1);
        }
        return strVal;
    }

    private boolean doCompare(String cellValue, String operator, String value) {
        switch (operator.toUpperCase()) {
            case "=":
                return cellValue != null && cellValue.equals(value);
            case "!=":
            case "<>":
                return cellValue == null || !cellValue.equals(value);
            case ">":
                return cellValue != null && compareNumeric(cellValue, value) > 0;
            case "<":
                return cellValue != null && compareNumeric(cellValue, value) < 0;
            case ">=":
                return cellValue != null && compareNumeric(cellValue, value) >= 0;
            case "<=":
                return cellValue != null && compareNumeric(cellValue, value) <= 0;
            case "LIKE": {
                if (cellValue == null) return false;
                String pattern = value.toLowerCase().replace("%", "");
                return cellValue.toLowerCase().contains(pattern);
            }
            default:
                return true;
        }
    }

    private String reverseOperator(String op) {
        switch (op.toUpperCase()) {
            case ">": return "<";
            case "<": return ">";
            case ">=": return "<=";
            case "<=": return ">=";
            default: return op;
        }
    }

    private int compareNumeric(String a, String b) {
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    @SuppressWarnings("unchecked")
    protected void applyDataMasking(List<List<String>> rows, List<String> columns, String maskingRule) {
        if (rows == null || rows.isEmpty() || maskingRule == null || maskingRule.isBlank()) {
            return;
        }
        try {
            java.util.Map<String, Object> ruleMap;
            if (maskingRule.trim().startsWith("{")) {
                com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ruleMap = objectMapper.readValue(maskingRule, java.util.Map.class);
            } else {
                return;
            }
            java.util.Map<String, Integer> colIndexMap = new java.util.LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colIndexMap.put(columns.get(i).toLowerCase(), i);
            }
            for (java.util.Map.Entry<String, Object> entry : ruleMap.entrySet()) {
                String colName = entry.getKey().toLowerCase();
                Integer colIdx = colIndexMap.get(colName);
                if (colIdx == null) continue;
                Object ruleValue = entry.getValue();
                String maskType;
                java.util.List<Object> maskParams = null;
                if (ruleValue instanceof String) {
                    maskType = (String) ruleValue;
                } else if (ruleValue instanceof java.util.Map) {
                    java.util.Map<String, Object> ruleDetail = (java.util.Map<String, Object>) ruleValue;
                    maskType = (String) ruleDetail.getOrDefault("type", "partial");
                    Object params = ruleDetail.get("params");
                    if (params instanceof java.util.List) {
                        maskParams = (java.util.List<Object>) params;
                    }
                } else {
                    continue;
                }
                for (List<String> row : rows) {
                    if (colIdx < row.size()) {
                        String original = row.get(colIdx);
                        if (original == null || "NULL".equals(original)) continue;
                        row.set(colIdx, applyMask(original, maskType, maskParams));
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("数据脱敏规则解析失败: {}", e.getMessage());
        }
    }

    protected String applyMask(String original, String maskType, java.util.List<Object> maskParams) {
        if (original == null || original.isEmpty()) return original;
        switch (maskType.toLowerCase()) {
            case "full":
                return "*".repeat(original.length());
            case "partial":
                int keepFirst = 2;
                int keepLast = 2;
                if (maskParams != null && maskParams.size() >= 2) {
                    try { keepFirst = ((Number) maskParams.get(0)).intValue(); } catch (Exception ignored) {}
                    try { keepLast = ((Number) maskParams.get(1)).intValue(); } catch (Exception ignored) {}
                }
                if (original.length() <= keepFirst + keepLast) return "*".repeat(original.length());
                StringBuilder sb = new StringBuilder();
                sb.append(original, 0, Math.min(keepFirst, original.length()));
                for (int i = keepFirst; i < original.length() - keepLast; i++) {
                    sb.append('*');
                }
                if (keepLast > 0 && original.length() > keepFirst) {
                    sb.append(original.substring(original.length() - keepLast));
                }
                return sb.toString();
            case "email":
                int atIndex = original.indexOf('@');
                if (atIndex > 0) {
                    return original.charAt(0) + "***" + original.substring(atIndex);
                }
                return applyMask(original, "partial", maskParams);
            case "phone":
                if (original.length() >= 7) {
                    return original.substring(0, 3) + "****" + original.substring(original.length() - 4);
                }
                return applyMask(original, "partial", maskParams);
            case "idcard":
                if (original.length() >= 8) {
                    int maskLen = original.length() - 8;
                    return original.substring(0, 4) + "*".repeat(maskLen) + original.substring(original.length() - 4);
                }
                return applyMask(original, "partial", maskParams);
            default:
                return applyMask(original, "partial", maskParams);
        }
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
