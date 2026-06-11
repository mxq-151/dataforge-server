package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import com.bigdata.modules.system.entity.SysUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.truncate.Truncate;

/**
 * 执行策略抽象基类，提供公共的权限校验、行级过滤、数据脱敏等安全能力。
 * <p>
 * 所有需要SQL权限控制的执行策略（JDBC、REST等）都应继承此类，
 * 以复用统一的DDL/DML权限检查、行过滤和数据脱敏逻辑。
 * </p>
 */
public abstract class AbstractBaseExecutionStrategy implements ExecutionStrategy {

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

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** 子类必须提供自身的日志器 */
    protected abstract Logger getLog();

    // ==================== 权限校验 ====================

    /**
     * 默认的权限校验实现：DDL权限检查 + DML表级权限检查。
     * 子类可按需覆写此方法以增加额外的校验逻辑（建议先调用 super.checkPermission()）。
     */
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
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }

    // ==================== DDL检测 ====================

    protected static class DdlInfo {
        String ddlType;
        String databaseName;
        String tableName;
    }

    /**
     * 使用JSqlParser检测SQL是否为DDL语句（CREATE/ALTER/DROP/TRUNCATE TABLE）
     */
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

    // ==================== DDL权限判断 ====================

    /**
     * 判断用户是否拥有DDL执行权限（超级管理员 / admin角色 / ops角色）
     */
    protected boolean hasDdlPermission(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
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

    // ==================== DML表级权限检查 ====================

    /**
     * 对DML语句（SELECT/INSERT/UPDATE/DELETE）进行表级权限校验
     * 超级管理员自动跳过检查
     */
    protected void checkDmlTablePermission(CodeExecutionContext context, String sql, String executionId) {
        try {
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
                        datasourceName, dbName, tableRef.tableName, operation);
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

    // ==================== SQL解析辅助方法 ====================

    protected static class TableRef {
        String tableName;
        String databaseName;
    }

    protected static class SelectPermissionInfo {
        TableRef tableRef;
        String operation;
    }

    /**
     * 从SELECT语句中提取涉及的表引用列表
     */
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

    /**
     * 通过反射动态提取FromItem中的表信息（支持嵌套SubJoin/SubSelect）
     */
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

    /**
     * 提取SELECT语句的权限信息（用于后续行级过滤和脱敏）
     */
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

    // ==================== 行级过滤 ====================

    /**
     * 根据行级过滤SQL对查询结果进行行过滤
     *
     * @param rows         查询结果行数据
     * @param columns      列名列表
     * @param rowFilterSql 过滤条件SQL（支持WHERE前缀或裸表达式）
     * @return 过滤后的行数据
     */
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
            Map<String, Integer> colIndexMap = new LinkedHashMap<>();
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

    protected boolean evaluateRowFilterExpression(List<String> row, List<String> columns,
                                                   Map<String, Integer> colIndexMap, String filterSql) {
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

    protected boolean evaluateExpression(Expression expr, List<String> row, Map<String, Integer> colIndexMap) {
        if (expr == null) return true;
        if (expr instanceof AndExpression) {
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
                if (colIdx == null || colIdx >= row.size()) return true;
                String cellValue = row.get(colIdx);
                if ("NULL".equals(cellValue)) cellValue = null;
                List<Expression> items = ((net.sf.jsqlparser.expression.operators.relational.ExpressionList) rightItems).getExpressions();
                for (Expression item : items) {
                    String valStr = extractStringValue(item);
                    if (valStr != null && valStr.equals(cellValue)) return true;
                }
                return false;
            }
            return true;
        }
        getLog().debug("未支持的表达式类型: {}, 保留行", expr.getClass().getSimpleName());
        return true;
    }

    private boolean compareColumnValue(Expression leftExpr, Expression rightExpr, String operator,
                                       List<String> row, Map<String, Integer> colIndexMap) {
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
        if (colName == null || valueStr == null) return true;
        Integer colIdx = colIndexMap.get(colName.toLowerCase());
        if (colIdx == null || colIdx >= row.size()) return true;
        String cellValue = row.get(colIdx);
        if ("NULL".equals(cellValue)) cellValue = null;
        return doCompare(cellValue, operator, valueStr);
    }

    private String extractStringValue(Expression expr) {
        if (expr instanceof StringValue) return ((StringValue) expr).getValue();
        if (expr instanceof net.sf.jsqlparser.expression.LongValue) return String.valueOf(((net.sf.jsqlparser.expression.LongValue) expr).getValue());
        if (expr instanceof net.sf.jsqlparser.expression.DoubleValue) return String.valueOf(((net.sf.jsqlparser.expression.DoubleValue) expr).getValue());
        if (expr instanceof StringValue) return ((StringValue) expr).getValue();
        if (expr instanceof net.sf.jsqlparser.expression.NullValue) return null;
        String strVal = expr.toString();
        if (strVal.startsWith("'") && strVal.endsWith("'")) return strVal.substring(1, strVal.length() - 1);
        return strVal;
    }

    private boolean doCompare(String cellValue, String operator, String value) {
        switch (operator.toUpperCase()) {
            case "=": return cellValue != null && cellValue.equals(value);
            case "!=": case "<>": return cellValue == null || !cellValue.equals(value);
            case ">": return cellValue != null && compareNumeric(cellValue, value) > 0;
            case "<": return cellValue != null && compareNumeric(cellValue, value) < 0;
            case ">=": return cellValue != null && compareNumeric(cellValue, value) >= 0;
            case "<=": return cellValue != null && compareNumeric(cellValue, value) <= 0;
            case "LIKE": {
                if (cellValue == null) return false;
                String pattern = value.toLowerCase().replace("%", "");
                return cellValue.toLowerCase().contains(pattern);
            }
            default: return true;
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

    // ==================== 数据脱敏 ====================

    /**
     * 根据脱敏规则对查询结果进行数据脱敏处理
     * <p>
     * 支持的规则格式：JSON对象，key为列名，value为规则类型或详细配置：
     * <pre>
     * {"phone": "full", "email": {"type": "partial", "params": [1,3]}}
     * </pre>
     * 支持的类型：full / partial / email / phone / idcard
     * </p>
     *
     * @param rows        查询结果行数据（原地修改）
     * @param columns     列名列表
     * @param maskingRule JSON格式的脱敏规则
     */
    @SuppressWarnings("unchecked")
    protected void applyDataMasking(List<List<String>> rows, List<String> columns, String maskingRule) {
        if (rows == null || rows.isEmpty() || maskingRule == null || maskingRule.isBlank()) return;
        try {
            Map<String, Object> ruleMap;
            if (maskingRule.trim().startsWith("{")) {
                ruleMap = objectMapper.readValue(maskingRule, Map.class);
            } else {
                return;
            }
            Map<String, Integer> colIndexMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colIndexMap.put(columns.get(i).toLowerCase(), i);
            }
            for (Map.Entry<String, Object> entry : ruleMap.entrySet()) {
                String colName = entry.getKey().toLowerCase();
                Integer colIdx = colIndexMap.get(colName);
                if (colIdx == null) continue;
                Object ruleValue = entry.getValue();
                String maskType;
                List<Object> maskParams = null;
                if (ruleValue instanceof String) {
                    maskType = (String) ruleValue;
                } else if (ruleValue instanceof Map) {
                    Map<String, Object> ruleDetail = (Map<String, Object>) ruleValue;
                    maskType = (String) ruleDetail.getOrDefault("type", "partial");
                    Object params = ruleDetail.get("params");
                    if (params instanceof List) maskParams = (List<Object>) params;
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

    /**
     * 对单个值应用脱敏
     */
    protected String applyMask(String original, String maskType, List<Object> maskParams) {
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
                for (int i = keepFirst; i < original.length() - keepLast; i++) sb.append('*');
                if (keepLast > 0 && original.length() > keepFirst) {
                    sb.append(original.substring(original.length() - keepLast));
                }
                return sb.toString();
            case "email":
                int atIndex = original.indexOf('@');
                if (atIndex > 0) return original.charAt(0) + "***" + original.substring(atIndex);
                return applyMask(original, "partial", maskParams);
            case "phone":
                if (original.length() >= 7) return original.substring(0, 3) + "****" + original.substring(original.length() - 4);
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
}
