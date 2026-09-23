package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.sql.CalciteDdlParser;
import com.bigdata.common.sql.SqlScriptSplitter;
import com.bigdata.common.sql.SqlTableCollector;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.service.MetadataSyncService;
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
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;

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

    @Autowired
    protected MetadataSyncService metadataSyncService;

    @Autowired
    protected com.bigdata.modules.metadata.service.MetadataSyncAlertService metadataSyncAlertService;

    @Autowired
    protected SqlPermissionRewriter sqlPermissionRewriter;

    @Autowired
    protected ExecutionLogListener executionLogListener;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** 子类必须提供自身的日志器 */
    protected abstract Logger getLog();

    // ==================== 权限校验 ====================

    /**
     * 默认的权限校验实现：逐条语句做 DDL 权限检查 + DML 表级权限检查。
     * <p>
     * 关键点（风险项 R4）：
     * <ul>
     *   <li>按分号拆成独立语句逐条校验。旧实现把整段脚本交给 JSqlParser，多语句脚本必然解析失败，
     *       从而整段放行——这是一条可以绕过表权限的路径。</li>
     *   <li>解析失败时改为<b>默认拒绝</b>（fail-close）：只要语句以 DML 关键字开头却无法解析，
     *       就拒绝执行并抛出明确原因，不再「打一条 debug 日志后放行」。</li>
     *   <li>Calcite 无法识别的 DDL 同样按默认拒绝处理，避免「解析能力不足」等价于「放行」。</li>
     * </ul>
     * 子类可按需覆写此方法以增加额外的校验逻辑（建议先调用 super.checkPermission()）。
     * </p>
     */
    @Override
    public void checkPermission(CodeExecutionContext context) {
        String sql = context.getCodeContent();
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        if (context.getCodeType() != CodeType.SQL) {
            // Scala / Python / Jar 等以任意代码访问数据源，不存在可解析的 SQL 语句；
            // 对这些内容做 SQL 解析只会产生误判（例如 Python 里出现 select = {} 这类标识符）。
            getLog().debug("代码类型 {} 非 SQL，跳过语句级权限校验", context.getCodeType());
            return;
        }

        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(sql);
        if (spans.isEmpty()) {
            return;
        }

        boolean ddlAllowed = hasDdlPermission(context.getSubmitBy());
        for (SqlScriptSplitter.Span span : spans) {
            String statement = span.text(sql);
            if (SqlScriptSplitter.isCommentOrBlank(statement)) {
                continue;
            }

            // DDL权限检查
            DdlInfo ddlInfo = detectDdlStatement(statement, context.getPrimaryStorageEngine());
            if (ddlInfo != null) {
                if (!ddlAllowed) {
                    getLog().warn("用户[{}]无DDL执行权限, 拒绝执行: {}", context.getSubmitBy(), context.getSubmitByName());
                    throw new SecurityException("权限不足：只有超级管理员和运维人员才能执行DDL语句（CREATE/ALTER/DROP/TRUNCATE）");
                }
                continue;
            }

            // Calcite 未识别为 DDL：若首关键字是 DDL 关键字，说明解析能力不足，按默认拒绝（fail-close）
            String keyword = SqlScriptSplitter.firstKeyword(statement);
            if (DDL_KEYWORDS.contains(keyword)) {
                if (!ddlAllowed) {
                    getLog().warn("用户[{}]提交的语句以DDL关键字[{}]开头但无法被解析, 按默认拒绝处理", context.getSubmitBy(), keyword);
                    throw new SecurityException(
                            "权限不足：语句以 " + keyword + " 开头（DDL），且平台无法解析该语句，按默认拒绝处理");
                }
                getLog().warn("语句以DDL关键字[{}]开头但平台无法解析, 已跳过元数据同步: {}", keyword,
                        abbreviate(statement));
                continue;
            }

            // DML表级权限检查
            checkDmlTablePermission(context, statement, null);
        }

        // 行级过滤 / 列白名单：下发前改写 SQL，让约束在引擎侧生效
        applyRowColumnRewrite(context);
    }

    /**
     * 把行级过滤条件与列白名单改写到下发内容里（风险项 R4）。
     * <p>
     * 为什么所有引擎统一在下发前改写，而不是只依赖「结果集返回后过滤」：
     * <ul>
     *   <li>Spark / Flink / HBase 由外部进程执行，服务端根本拿不到结果集；</li>
     *   <li>即便拿到结果集，聚合类查询（count / sum）的数值也不受后过滤影响，
     *       会泄露被行级权限挡住的行的口径。</li>
     * </ul>
     * 后过滤仍然保留为第二道防线（见 {@link #applyRowFilterAndMasking}），用于
     * 无法改写时兜底；改写生效时刻意跳过行过滤的二次应用，避免对已过滤结果重复过滤。
     * </p>
     */
    protected void applyRowColumnRewrite(CodeExecutionContext context) {
        if (sqlPermissionRewriter == null) {
            return;
        }
        String rewritten = sqlPermissionRewriter.rewrite(context);
        if (rewritten != null && !rewritten.equals(context.getCodeContent())) {
            context.setEffectiveCodeContent(rewritten);
            getLog().info("已按行/列级权限改写下发SQL: executionId={}", context.getExecutionId());
            if (executionLogListener != null) {
                executionLogListener.publishLog(context.getExecutionId(),
                        "[权限] 已按行级过滤/列级权限改写下发的SQL");
            }
        }
    }

    /** DDL 关键字集合：这些关键字开头的语句若无法被 Calcite 识别，需要按默认拒绝处理。 */
    private static final Set<String> DDL_KEYWORDS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT", "GRANT", "REVOKE", "ANALYZE");

    /** 以这些关键字开头的语句若解析失败，按默认拒绝处理（fail-close）。 */
    private static final Set<String> DML_KEYWORDS = Set.of(
            "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "UPSERT", "VALUES", "TABLE");

    private String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsLanguage(codeType);
    }

    // ==================== DDL检测 ====================

    /**
     * Hive表级属性（从CREATE TABLE语句的Hive特有子句中提取）
     */
    public static class HiveTableAttributes {
        public String tableComment;       // TBLPROPERTIES 中的 'comment' 值
        public String tableType;          // EXTERNAL → "EXTERNAL_TABLE", 否则 "MANAGED_TABLE"
        public String storageFormat;      // STORED AS 后面的格式（TEXTFILE/ORC/PARQUET等）
        public String location;           // LOCATION '...' 的值
        public String fieldDelimiter;     // FIELDS TERMINATED BY '...' 的值
        public Map<String, String> tblProperties; // TBLPROPERTIES 中所有键值对
        public String compressionCodec;   // 压缩算法
        public boolean isPartitioned;     // 是否分区表
        public String partitionColumns;   // 分区列（逗号分隔）
    }

    protected static class DdlInfo {
        String ddlType;
        String databaseName;
        String tableName;
        /** RENAME的新数据库名（仅RENAME操作有效，可能为null） */
        String newDatabaseName;
        /** RENAME的新表名（仅RENAME操作有效） */
        String newTableName;
        HiveTableAttributes hiveAttributes;
    }

    /**
     * 使用Apache Calcite检测SQL是否为DDL语句（CREATE/ALTER/DROP/TRUNCATE TABLE）。
     * <p>
     * 支持标准SQL和多种引擎的DDL语法（Hive、Doris、StarRocks、ClickHouse等）。
     * 通过CalciteDdlParser进行引擎自适应预处理剥离后再解析，并提取引擎特定表级属性。
     *
     * @see com.bigdata.common.sql.CalciteDdlParser
     */
    protected DdlInfo detectDdlStatement(String sql, DatasourceType engineType) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }
        getLog().info("[DDL-DEBUG-D1] detectDdlStatement 入口(Calcite), engine={}, SQL前100字: {}",
                engineType, sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);
        try {
            CalciteDdlParser.DdlInfo calciteInfo = CalciteDdlParser.detect(sql, engineType);
            if (calciteInfo == null) {
                return null;
            }

            getLog().info("[DDL-DEBUG-D1] Calcite解析成功: type={}, db={}, table={}, engine={}, fallback={}",
                    calciteInfo.getDbType(), calciteInfo.getDatabaseName(), calciteInfo.getTableName(),
                    calciteInfo.getEngineType(), calciteInfo.isFallbackMode());

            // 转换为内部 DdlInfo
            DdlInfo info = new DdlInfo();
            info.ddlType = calciteInfo.getDbType();
            info.databaseName = calciteInfo.getDatabaseName();
            info.tableName = calciteInfo.getTableName();
            info.newDatabaseName = calciteInfo.getNewDatabaseName();
            info.newTableName = calciteInfo.getNewTableName();

            // 转换引擎特定属性
            CalciteDdlParser.TableAttributes calciteAttrs = calciteInfo.getTableAttributes();
            if (calciteAttrs != null) {
                info.hiveAttributes = new HiveTableAttributes();
                info.hiveAttributes.tableComment = calciteAttrs.tableComment;
                info.hiveAttributes.tableType = calciteAttrs.tableType;
                info.hiveAttributes.storageFormat = calciteAttrs.storageFormat;
                info.hiveAttributes.location = calciteAttrs.location;
                info.hiveAttributes.fieldDelimiter = calciteAttrs.fieldDelimiter;
                info.hiveAttributes.tblProperties = calciteAttrs.tblProperties;
                info.hiveAttributes.compressionCodec = calciteAttrs.compressionCodec;
                info.hiveAttributes.isPartitioned = calciteAttrs.isPartitioned;
                info.hiveAttributes.partitionColumns = calciteAttrs.partitionColumns;

                getLog().info("[DDL-DEBUG-D1] 提取到表属性: engine={}, type={}, comment={}, storageFormat={}, location={}",
                        calciteAttrs.engineType, info.hiveAttributes.tableType, info.hiveAttributes.tableComment,
                        info.hiveAttributes.storageFormat, info.hiveAttributes.location);
            }

            return info;
        } catch (Exception e) {
            getLog().error("[DDL-DEBUG-D1] Calcite DDL解析失败: {}", e.getMessage(), e);
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
     * 对<b>单条</b> DML 语句（SELECT/INSERT/UPDATE/DELETE）进行表级权限校验。
     * 超级管理员自动跳过检查。
     * <p>
     * 覆盖范围：语句中出现的所有表引用——FROM 与各 JOIN 右侧、FROM 子句里的派生表、
     * WHERE / HAVING / ON / 投影列中的子查询、UNION 各分支与 CTE。
     * 写操作的目标表按写权限校验（INSERT/UPDATE/DELETE），其余参与的表按 SELECT 校验。
     * </p>
     * <p>
     * fail-close（风险项 R4）：语句以 DML 关键字开头却解析失败时拒绝执行，
     * 不再「打一条 debug 日志后放行」。
     * </p>
     */
    protected void checkDmlTablePermission(CodeExecutionContext context, String sql, String executionId) {
        if (hasDdlPermission(context.getSubmitBy())) {
            return;
        }
        net.sf.jsqlparser.statement.Statement jsqlStmt;
        try {
            jsqlStmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            String keyword = SqlScriptSplitter.firstKeyword(sql);
            if (DML_KEYWORDS.contains(keyword)) {
                getLog().error("DML权限检查解析失败, 按默认拒绝处理(fail-close): keyword={}, SQL={}, 原因={}",
                        keyword, abbreviate(sql), e.getMessage());
                throw new SecurityException(String.format(
                        "权限校验未通过：语句以 %s 开头但无法被解析，出于安全考虑已拒绝执行。"
                                + "请确认语句写法，或联系管理员。", keyword));
            }
            getLog().warn("非DML语句解析失败, 跳过表级权限检查: keyword={}, SQL={}, 原因={}",
                    keyword, abbreviate(sql), e.getMessage());
            return;
        }

        try {
            String operation = primaryOperation(jsqlStmt);
            if (operation == null) {
                getLog().debug("SQL语句不涉及DML表操作: {}", abbreviate(sql));
                return;
            }
            List<Table> writeTargets = writeTargets(jsqlStmt);
            List<Table> allTables = SqlTableCollector.statementTables(jsqlStmt);
            if (allTables.isEmpty()) {
                getLog().debug("SQL语句中未识别到表引用: {}", abbreviate(sql));
                return;
            }

            String datasourceName = context.getPrimaryDatasourceConfig() != null
                    ? context.getPrimaryDatasourceConfig().getDatasourceName() : null;
            String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

            for (Table table : allTables) {
                String dbName = SqlTableCollector.databaseName(table) != null
                        ? SqlTableCollector.databaseName(table) : defaultDatabase;
                String requiredOp = isSameTable(writeTargets, table) ? operation
                        : DataAccessPermissionService.TABLE_OP_SELECT;
                boolean hasPermission = dataAccessPermissionService.hasTablePermission(
                        datasourceName, dbName, table.getName(), requiredOp);
                if (!hasPermission) {
                    getLog().warn("用户[{}]无权对表 {}.{} 执行 {} 操作",
                            context.getSubmitBy(), dbName, table.getName(), requiredOp);
                    throw new SecurityException(String.format(
                            "权限不足：无权对表 %s.%s 执行 %s 操作",
                            dbName, table.getName(), requiredOp));
                }
                getLog().debug("用户[{}]拥有表 {}.{} 的 {} 权限",
                        context.getSubmitBy(), dbName, table.getName(), requiredOp);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // 语句已解析成功，失败发生在权限判定阶段——同样属于「无法判定」，
            // 按默认拒绝处理（fail-close），避免静默放行。
            getLog().error("DML权限检查异常, 按默认拒绝处理(fail-close): SQL={}", abbreviate(sql), e);
            throw new SecurityException("权限校验未通过：权限判定过程异常，出于安全考虑已拒绝执行。");
        }
    }

    /** 语句的主操作类型，非 DML 时返回 null。 */
    private String primaryOperation(net.sf.jsqlparser.statement.Statement stmt) {
        if (stmt instanceof Select) {
            return DataAccessPermissionService.TABLE_OP_SELECT;
        }
        if (stmt instanceof Insert) {
            return DataAccessPermissionService.TABLE_OP_INSERT;
        }
        if (stmt instanceof Update) {
            return DataAccessPermissionService.TABLE_OP_UPDATE;
        }
        if (stmt instanceof Delete) {
            return DataAccessPermissionService.TABLE_OP_DELETE;
        }
        return null;
    }

    /** 语句的写操作目标表（INSERT/UPDATE/DELETE 的目标，含多表 DELETE 的目标列表）。 */
    private List<Table> writeTargets(net.sf.jsqlparser.statement.Statement stmt) {
        List<Table> targets = new ArrayList<>();
        if (stmt instanceof Insert) {
            Table t = ((Insert) stmt).getTable();
            if (t != null) {
                targets.add(t);
            }
        } else if (stmt instanceof Update) {
            Table t = ((Update) stmt).getTable();
            if (t != null) {
                targets.add(t);
            }
        } else if (stmt instanceof Delete) {
            Delete delete = (Delete) stmt;
            if (delete.getTable() != null) {
                targets.add(delete.getTable());
            }
            if (delete.getTables() != null) {
                targets.addAll(delete.getTables());
            }
        }
        return targets;
    }

    private boolean isSameTable(List<Table> targets, Table table) {
        for (Table t : targets) {
            if (t == table) {
                return true;
            }
        }
        return false;
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
     * 从SELECT语句中提取涉及的表引用列表。
     * <p>
     * 覆盖 FROM 与各 JOIN 右侧、FROM 子句中的派生表、各层子查询、UNION 分支与 CTE。
     * 旧实现依赖 {@code SubJoin}/{@code SubSelect} 类名反射，而 JSqlParser 4.9 已把这两个类
     * 改名为 {@code ParenthesedFromItem}/{@code ParenthesedSelect}，导致 JOIN 与子查询中的表
     * 完全漏检，现改由 {@link SqlTableCollector} 统一收集。
     * </p>
     */
    protected List<TableRef> extractTablesFromSelect(Select select) {
        List<TableRef> tableRefs = new ArrayList<>();
        try {
            for (Table table : SqlTableCollector.readTables(select)) {
                TableRef ref = new TableRef();
                ref.tableName = table.getName();
                ref.databaseName = SqlTableCollector.databaseName(table);
                tableRefs.add(ref);
            }
        } catch (Exception e) {
            getLog().warn("提取SELECT语句的表引用失败: {}", e.getMessage());
        }
        return tableRefs;
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
     * 根据行级过滤SQL对查询结果进行行过滤（原地修改 rows 列表，删除不满足条件的行）。
     *
     * @param rows         查询结果行数据（原地修改）
     * @param columns      列名列表
     * @param rowFilterSql 过滤条件SQL（支持WHERE前缀或裸表达式）
     * @return 过滤后的行数据（与入参为同一引用）
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
            Iterator<List<String>> it = rows.iterator();
            while (it.hasNext()) {
                List<String> row = it.next();
                boolean keep;
                try {
                    keep = evaluateRowFilterExpression(row, columns, colIndexMap, finalFilterExpr);
                } catch (Exception e) {
                    getLog().debug("行过滤表达式求值失败，保留行: {}", e.getMessage());
                    keep = true;
                }
                if (!keep) {
                    it.remove();
                }
            }
            return rows;
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

    // ==================== DDL元数据同步 & 行过滤/脱敏（所有SQL策略共用）====================

    /**
     * DDL语句执行后的元数据同步处理。
     * 检测SQL是否为DDL，若是则通过MetadataSyncService同步对应表的元数据。
     * 所有SQL执行策略（JDBC、Kyuubi等）在execute成功后均可调用此方法。
     */
    protected void handleDdlMetadataSync(String sql, CodeExecutionContext context, String executionId) {
        getLog().info("[DDL-DEBUG-D2] handleDdlMetadataSync 入口, executionId={}", executionId);
        DdlInfo ddlInfo = detectDdlStatement(sql, context.getPrimaryStorageEngine());
        if (ddlInfo == null) {
            getLog().warn("[DDL-DEBUG-D2] ⚠️ ddlInfo为NULL! 元数据同步被跳过! SQL不是DDL或Calcite解析失败! executionId={}", executionId);
            return;
        }
        getLog().info("[DDL-DEBUG-D2] DDL检测通过: type={}, db={}, table={}", ddlInfo.ddlType, ddlInfo.databaseName, ddlInfo.tableName);

        // RENAME TABLE 特殊处理: 先删除旧表元数据, 再同步新表元数据
        if ("RENAME".equalsIgnoreCase(ddlInfo.ddlType)) {
            String oldDb = ddlInfo.databaseName;
            String oldTable = ddlInfo.tableName;
            String newDb = ddlInfo.newDatabaseName != null ? ddlInfo.newDatabaseName : oldDb;
            String newTable = ddlInfo.newTableName;

            String renameMsg = "DDL语句执行成功: RENAME " +
                    (oldDb != null ? oldDb + "." : "") + oldTable + " TO " +
                    (newDb != null ? newDb + "." : "") + newTable;
            getLog().info(renameMsg + ", 开始同步元数据");
            executionLogListener.publishLog(executionId, "[DDL] " + renameMsg);

            try {
                boolean dropOk = metadataSyncService.syncSingleTableMetadata(
                        context.getPrimaryDatasourceConfig().getId(),
                        context.getPrimaryStorageEngine(),
                        oldDb, oldTable, "DROP", null);
                boolean createOk = metadataSyncService.syncSingleTableMetadata(
                        context.getPrimaryDatasourceConfig().getId(),
                        context.getPrimaryStorageEngine(),
                        newDb, newTable, "CREATE", null);
                if (dropOk && createOk) {
                    getLog().info("元数据同步成功: RENAME {}.{} TO {}.{}", oldDb, oldTable, newDb, newTable);
                    executionLogListener.publishLog(executionId, "[元数据] 同步成功: RENAME " + oldTable + " TO " + newTable);
                } else {
                    getLog().warn("元数据同步失败: RENAME {}.{} TO {}.{}", oldDb, oldTable, newDb, newTable);
                    executionLogListener.publishLog(executionId, "[元数据] 同步失败: RENAME " + oldTable + " TO " + newTable);
                    // 风险项 R1：同步失败不再只落一条 warn 日志，升级为可检索的告警记录
                    metadataSyncAlertService.alertSingleTableSyncFailed(
                            context.getPrimaryDatasourceConfig().getId(),
                            context.getPrimaryStorageEngine(),
                            newDb, newTable, "RENAME",
                            "RENAME 同步失败(dropOk=" + dropOk + ", createOk=" + createOk + ")");
                }
            } catch (Exception syncEx) {
                getLog().error("元数据同步异常: RENAME {}.{} TO {}.{}", oldDb, oldTable, newDb, newTable, syncEx);
                executionLogListener.publishLog(executionId, "[元数据] 同步异常: " + syncEx.getMessage());
                metadataSyncAlertService.alertSingleTableSyncFailed(
                        context.getPrimaryDatasourceConfig().getId(),
                        context.getPrimaryStorageEngine(),
                        newDb, newTable, "RENAME", "RENAME 同步异常: " + syncEx.getMessage());
            }
            return;
        }

        String ddlMsg = "DDL语句执行成功: " + ddlInfo.ddlType + " " +
                (ddlInfo.databaseName != null ? ddlInfo.databaseName + "." : "") + ddlInfo.tableName;
        getLog().info(ddlMsg + ", 开始同步元数据");
        executionLogListener.publishLog(executionId, "[DDL] " + ddlMsg);
        try {
            boolean syncSuccess = metadataSyncService.syncSingleTableMetadata(
                    context.getPrimaryDatasourceConfig().getId(),
                    context.getPrimaryStorageEngine(),
                    ddlInfo.databaseName, ddlInfo.tableName, ddlInfo.ddlType,
                    ddlInfo.hiveAttributes);
            if (syncSuccess) {
                getLog().info("元数据同步成功: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                executionLogListener.publishLog(executionId, "[元数据] 同步成功: " + ddlInfo.tableName);
            } else {
                getLog().warn("元数据同步失败: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                executionLogListener.publishLog(executionId, "[元数据] 同步失败: " + ddlInfo.tableName);
                // 风险项 R1：同步失败不再只落一条 warn 日志，升级为可检索的告警记录
                metadataSyncAlertService.alertSingleTableSyncFailed(
                        context.getPrimaryDatasourceConfig().getId(),
                        context.getPrimaryStorageEngine(),
                        ddlInfo.databaseName, ddlInfo.tableName, ddlInfo.ddlType,
                        "DDL 执行后元数据同步返回失败");
            }
        } catch (Exception syncEx) {
            getLog().error("元数据同步异常: {}.{}", ddlInfo.databaseName, ddlInfo.tableName, syncEx);
            executionLogListener.publishLog(executionId, "[元数据] 同步异常: " + syncEx.getMessage());
            metadataSyncAlertService.alertSingleTableSyncFailed(
                    context.getPrimaryDatasourceConfig().getId(),
                    context.getPrimaryStorageEngine(),
                    ddlInfo.databaseName, ddlInfo.tableName, ddlInfo.ddlType,
                    "DDL 执行后元数据同步异常: " + syncEx.getMessage());
        }
    }

    /**
     * 对查询结果应用行级过滤（第二道防线）、数据脱敏与列级裁剪。
     * 所有能拿到结果集的执行策略（JDBC、Kyuubi）在返回SELECT结果前调用。
     * <p>
     * 行级过滤是「兜底」：如果 {@link #applyRowColumnRewrite} 已经把行过滤条件注入到
     * 下发 SQL 并由引擎执行，这里不再重复过滤——重复过滤不仅无意义，多表场景下还会因为
     * 后过滤只能取「第一张表」的条件而对已过滤的结果产生错误裁剪。
     * 脱敏与列裁剪不受影响，始终执行。
     * </p>
     */
    protected void applyRowFilterAndMasking(List<List<String>> rows, List<String> columns,
                                           String sql, CodeExecutionContext context) {
        String datasourceName = context.getPrimaryDatasourceConfig().getDatasourceName();
        String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

        SelectPermissionInfo permInfo = extractSelectPermissionInfo(sql, datasourceName, defaultDatabase);
        if (permInfo != null && permInfo.tableRef != null) {
            String dbName = permInfo.tableRef.databaseName != null ?
                    permInfo.tableRef.databaseName : defaultDatabase;
            String tableName = permInfo.tableRef.tableName;
            // 行级权限（指定行）：已在下发前注入到 SQL 时不再重复过滤
            boolean rowFilterAlreadyInjected = context.isCodeRewritten();
            String mergedRowFilterSql = rowFilterAlreadyInjected ? null
                    : dataAccessPermissionService.getMergedRowFilterSql(
                            datasourceName, dbName, tableName);
            if (rowFilterAlreadyInjected) {
                getLog().debug("行级过滤已在下发前注入SQL，跳过结果集二次过滤: {}.{}", dbName, tableName);
            }
            if (mergedRowFilterSql != null && !mergedRowFilterSql.isBlank()) {
                int beforeSize = rows.size();
                rows = applyRowFilter(rows, columns, mergedRowFilterSql);
                getLog().info("行级过滤: {} -> {} 行, 过滤条件: {}", beforeSize, rows.size(), mergedRowFilterSql);
            }
            // 数据脱敏（列级掩码）
            SysTablePermission mergedPerm = dataAccessPermissionService.getMergedTablePermission(
                    datasourceName, dbName, tableName);
            if (mergedPerm != null && mergedPerm.getDataMaskingRule() != null && !mergedPerm.getDataMaskingRule().isBlank()) {
                applyDataMasking(rows, columns, mergedPerm.getDataMaskingRule());
                getLog().info("已应用数据脱敏规则, 表: {}.{}", dbName, tableName);
            }
            // 列级权限（指定列）：隐藏未授权列
            applyColumnPermission(rows, columns, datasourceName, dbName, tableName);
        }
    }

    /**
     * 根据列级权限白名单对查询结果进行列裁剪（指定列）。
     * <p>
     * 调用 {@link DataAccessPermissionService#getAllowedColumns} 获取当前用户对该表允许可见的列，
     * 返回 {@code null}(所有列可见)时不做任何处理，否则仅保留白名单内的列及其数据。
     * </p>
     *
     * @param rows    查询结果行数据（原地修改）
     * @param columns 列名列表（原地修改）
     */
    protected void applyColumnPermission(List<List<String>> rows, List<String> columns,
                                         String datasourceName, String databaseName, String tableName) {
        java.util.Set<String> allowed = dataAccessPermissionService.getAllowedColumns(
                datasourceName, databaseName, tableName);
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        Set<String> allowedLower = allowed.stream()
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        List<Integer> keepIndexes = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) != null && allowedLower.contains(columns.get(i).toLowerCase())) {
                keepIndexes.add(i);
            }
        }
        if (keepIndexes.size() == columns.size()) {
            return;
        }
        // 从后往前删除未授权列，避免索引错位
        Set<Integer> keepSet = new HashSet<>(keepIndexes);
        for (int i = columns.size() - 1; i >= 0; i--) {
            if (!keepSet.contains(i)) {
                columns.remove(i);
                for (List<String> row : rows) {
                    if (i < row.size()) {
                        row.remove(i);
                    }
                }
            }
        }
        getLog().info("列级权限过滤: 表 {}.{} 仅保留授权列 {} ", databaseName, tableName, columns);
    }
}
