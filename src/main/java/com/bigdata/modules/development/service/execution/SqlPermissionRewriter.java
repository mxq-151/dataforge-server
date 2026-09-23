package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.sql.SqlScriptSplitter;
import com.bigdata.common.sql.SqlTableCollector;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQL 下发前权限改写器（风险项 R4 的闭合手段之一）。
 * <p>
 * 背景：行级过滤与列级权限在 JDBC / Kyuubi 路径上是「结果集返回后再过滤」，
 * 而 Spark / Flink / HBase 三条路径把作业交给外部进程执行，服务端拿不到结果集，
 * 无法做后过滤。本类在<b>提交之前</b>改写 SQL，把权限约束下沉到引擎侧执行：
 * <ul>
 *   <li><b>行级过滤</b>：把该表的行过滤条件 AND 进查询的 WHERE 子句；
 *       多表查询时先用该表的别名/表名限定过滤条件里的列。</li>
 *   <li><b>列级权限</b>：把投影列裁剪为列白名单；{@code SELECT *} 展开成白名单列。</li>
 * </ul>
 * </p>
 * <p>
 * <b>可安全改写的边界</b>（超出边界一律 fail-close，抛出 {@link SecurityException}，
 * 不做「改不掉就放行」）：
 * <ul>
 *   <li>列裁剪只允许发生在单表、且处于可裁剪位置（顶层查询）的 SELECT 上；
 *       集合运算分支、子查询里的列裁剪会改变外层可见的列集合，直接拒绝。</li>
 *   <li>行过滤在多表查询上注入时，要求该层 JOIN 全部为内连接，
 *       避免把 LEFT JOIN 的语义改成 INNER JOIN。</li>
 *   <li>投影列里出现函数/表达式且引用了白名单之外的列时拒绝执行。</li>
 * </ul>
 * </p>
 * <p>
 * 只处理 SQL 代码类型；Scala / Python / Jar 等以任意代码方式访问数据源的作业不在本层能力范围内。
 * </p>
 */
@Component
public class SqlPermissionRewriter {

    private static final Logger log = LoggerFactory.getLogger(SqlPermissionRewriter.class);

    private final DataAccessPermissionService permissionService;

    @Autowired
    public SqlPermissionRewriter(DataAccessPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 对执行上下文中的 SQL 做权限改写。
     *
     * @return 改写后的脚本；无需改写（非 SQL 类型、无权限约束、无变化）时返回 {@code null}
     * @throws SecurityException 命中了权限约束但无法安全改写时
     */
    public String rewrite(CodeExecutionContext context) {
        if (context == null || context.getCodeType() != CodeType.SQL) {
            return null;
        }
        DatasourceConfig config = context.getPrimaryDatasourceConfig();
        String datasourceName = config != null ? config.getDatasourceName() : null;
        return rewriteScript(context.getCodeContent(), datasourceName,
                context.getPrimaryDatasourceDatabaseName());
    }

    /**
     * 对脚本做权限改写，保留原文中的注释、空白与分隔符。
     *
     * @param script         用户提交的 SQL 脚本
     * @param datasourceName 主数据源名（用于匹配表权限，可为 null）
     * @param defaultDatabase 未显式指定库名时的默认库（可为 null）
     * @return 改写后的脚本；无任何改写时返回 {@code null}
     */
    public String rewriteScript(String script, String datasourceName, String defaultDatabase) {
        if (script == null || script.isBlank()) {
            return null;
        }
        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(script);
        if (spans.isEmpty()) {
            return null;
        }
        RewriteContext ctx = new RewriteContext(datasourceName, defaultDatabase);
        StringBuilder out = new StringBuilder(script.length() + 64);
        int cursor = 0;
        boolean changedAny = false;
        for (SqlScriptSplitter.Span span : spans) {
            String statement = span.text(script);
            if (SqlScriptSplitter.isCommentOrBlank(statement)) {
                continue;
            }
            String replaced = rewriteStatement(statement, ctx);
            out.append(script, cursor, span.getStart());
            out.append(replaced != null ? replaced : statement);
            cursor = span.getEnd();
            changedAny = changedAny || replaced != null;
        }
        if (!changedAny) {
            return null;
        }
        out.append(script, cursor, script.length());
        return out.toString();
    }

    // ==================== 语句级 ====================

    private String rewriteStatement(String statement, RewriteContext ctx) {
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(statement);
        } catch (Exception e) {
            // 语句级权限校验已保证「以 DML 关键字开头且解析失败的语句」被拒绝，
            // 能走到这里的是解析器不认识的脚本命令（SET / ADD JAR / MSCK 等），无需改写。
            log.debug("语句无法解析，跳过权限改写: {}", e.getMessage());
            return null;
        }

        boolean changed;
        if (parsed instanceof Select) {
            changed = rewriteSelect((Select) parsed, ctx, true);
        } else if (parsed instanceof Insert) {
            Select source = ((Insert) parsed).getSelect();
            changed = source != null && rewriteSelect(source, ctx, false);
        } else if (parsed instanceof Update) {
            changed = rewriteWriteStatement(parsed, ctx);
        } else if (parsed instanceof Delete) {
            changed = rewriteWriteStatement(parsed, ctx);
        } else {
            return null;
        }
        if (!changed) {
            return null;
        }
        try {
            String rewritten = parsed.toString();
            log.info("SQL权限改写生效: [{}] -> [{}]", abbreviate(statement), abbreviate(rewritten));
            return rewritten;
        } catch (Exception e) {
            log.error("SQL权限改写后序列化失败, 按默认拒绝处理", e);
            throw new SecurityException("权限改写失败：改写后的语句无法序列化，出于安全考虑已拒绝执行。");
        }
    }

    /** 写语句（UPDATE / DELETE）只改写其中读取数据的子查询，不改写写作用域。 */
    private boolean rewriteWriteStatement(Statement statement, RewriteContext ctx) {
        boolean changed = false;
        Expression where = null;
        List<Join> joins = null;
        if (statement instanceof Update) {
            Update update = (Update) statement;
            where = update.getWhere();
            joins = update.getJoins();
            if (update.getSelect() != null) {
                changed |= rewriteSelect(update.getSelect(), ctx, false);
            }
        } else if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            where = delete.getWhere();
            joins = delete.getJoins();
        }
        changed |= rewriteSubSelects(where, ctx);
        if (joins != null) {
            for (Join join : joins) {
                if (join != null) {
                    changed |= rewriteSubSelects(join.getOnExpression(), ctx);
                }
            }
        }
        return changed;
    }

    // ==================== SELECT 递归 ====================

    private boolean rewriteSelect(Select select, RewriteContext ctx, boolean allowColumnPrune) {
        if (select == null) {
            return false;
        }
        boolean changed = false;
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem wi : withItems) {
                if (wi != null && wi.getSelect() != null) {
                    changed |= rewriteSelect(wi.getSelect(), ctx, false);
                }
            }
        }
        if (select instanceof PlainSelect) {
            changed |= rewritePlainSelect((PlainSelect) select, ctx, allowColumnPrune);
        } else if (select instanceof SetOperationList) {
            List<Select> branches = ((SetOperationList) select).getSelects();
            if (branches != null) {
                for (Select branch : branches) {
                    // 集合运算各分支的投影列会决定结果列集合，不能在此裁剪
                    changed |= rewriteSelect(branch, ctx, false);
                }
            }
        } else if (select instanceof ParenthesedSelect) {
            Select inner = ((ParenthesedSelect) select).getSelect();
            if (inner != null) {
                changed |= rewriteSelect(inner, ctx, allowColumnPrune);
            }
        }
        return changed;
    }

    private boolean rewritePlainSelect(PlainSelect plainSelect, RewriteContext ctx, boolean allowColumnPrune) {
        boolean changed = false;
        List<Table> levelTables = SqlTableCollector.levelTables(plainSelect);

        // 1) 收集本层各表的行过滤条件，并处理列级权限
        List<String> rowFilters = new ArrayList<>();
        for (Table table : levelTables) {
            String database = SqlTableCollector.databaseName(table) != null
                    ? SqlTableCollector.databaseName(table) : ctx.defaultDatabase;
            String rowFilter = permissionService.getMergedRowFilterSql(
                    ctx.datasourceName, database, table.getName());
            Set<String> allowedColumns = permissionService.getAllowedColumns(
                    ctx.datasourceName, database, table.getName());

            if (rowFilter == null && allowedColumns == null) {
                continue;
            }
            log.info("命中权限约束: table={}.{}, rowFilter={}, allowedColumns={}",
                    database, table.getName(), rowFilter, allowedColumns);

            if (rowFilter != null && !rowFilter.isBlank()) {
                if (levelTables.size() == 1) {
                    rowFilters.add(rowFilter);
                } else {
                    requireInnerJoinsOnly(plainSelect, table);
                    rowFilters.add(qualifyRowFilter(rowFilter, table));
                }
            }

            if (allowedColumns != null) {
                if (allowedColumns.isEmpty()) {
                    throw new SecurityException("权限不足：表 " + table.getName()
                            + " 的列级权限为空，无法执行该查询");
                }
                if (!allowColumnPrune) {
                    throw new SecurityException("权限不足：表 " + table.getName()
                            + " 配置了列级权限，但该查询形态（集合运算 / 子查询）无法安全裁剪列，已拒绝执行。"
                            + "请改用等价的顶层单表查询。");
                }
                if (levelTables.size() != 1) {
                    throw new SecurityException("权限不足：表 " + table.getName()
                            + " 配置了列级权限，但该查询涉及多表，无法安全确定可裁剪的列集合，已拒绝执行。");
                }
                changed |= pruneColumns(plainSelect, table, allowedColumns);
            }
        }

        // 2) 递归处理本层里出现的子查询
        changed |= rewriteSubSelects(plainSelect.getWhere(), ctx);
        changed |= rewriteSubSelects(plainSelect.getHaving(), ctx);
        List<SelectItem<?>> items = plainSelect.getSelectItems();
        if (items != null) {
            for (SelectItem<?> item : items) {
                if (item != null) {
                    changed |= rewriteSubSelects(item.getExpression(), ctx);
                }
            }
        }
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join != null) {
                    changed |= rewriteSubSelects(join.getOnExpression(), ctx);
                }
            }
        }
        List<FromItem> leaves = new ArrayList<>();
        SqlTableCollector.leaves(plainSelect.getFromItem(), leaves);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                SqlTableCollector.leaves(join != null ? join.getRightItem() : null, leaves);
            }
        }
        for (FromItem leaf : leaves) {
            if (leaf instanceof ParenthesedSelect) {
                Select inner = ((ParenthesedSelect) leaf).getSelect();
                if (inner != null) {
                    changed |= rewriteSelect(inner, ctx, false);
                }
            }
        }

        // 3) 注入行级过滤条件
        if (!rowFilters.isEmpty()) {
            injectRowFilters(plainSelect, rowFilters);
            changed = true;
        }
        return changed;
    }

    private void injectRowFilters(PlainSelect plainSelect, List<String> rowFilters) {
        Expression combined = null;
        for (String filter : rowFilters) {
            Expression parsed = parseFilter(filter);
            combined = combined == null ? parsed : new AndExpression(combined, parsed);
        }
        if (combined == null) {
            return;
        }
        Expression existing = plainSelect.getWhere();
        Expression injected = new Parenthesis(combined);
        plainSelect.setWhere(existing == null
                ? injected
                : new AndExpression(new Parenthesis(existing), injected));
    }

    /**
     * 解析行级过滤条件。
     * <p>
     * 关键点：JSqlParser 对残缺条件非常宽松——{@code parseCondExpression("region =")} 会
     * 静默返回裸列 {@code region}，注入后等价于「只要 region 非空就放行」，把限制变成了放宽。
     * 因此这里在解析成功后追加一次「往返一致性」校验：把解析结果重新序列化并与原表达式做
     * 去空白 / 去大小写差异后的比较，不一致即说明条件被丢弃或改写，按默认拒绝处理。
     * </p>
     */
    private Expression parseFilter(String filter) {
        String expression = stripWherePrefix(filter);
        Expression parsed;
        try {
            parsed = CCJSqlParserUtil.parseCondExpression(expression);
        } catch (Exception e) {
            log.error("行级过滤条件无法解析, 按默认拒绝处理: [{}], 原因={}", filter, e.getMessage());
            throw new SecurityException(
                    "权限校验未通过：配置的行级过滤条件无法解析，出于安全考虑已拒绝执行。请管理员检查该表的行权限配置。");
        }
        if (!normalizeSql(expression).equals(normalizeSql(parsed.toString()))) {
            log.error("行级过滤条件被解析器静默简化, 按默认拒绝处理: 输入=[{}], 解析后=[{}]",
                    expression, parsed);
            throw new SecurityException(
                    "权限校验未通过：配置的行级过滤条件无法解析（解析后条件被丢弃或简化），出于安全考虑已拒绝执行。"
                            + "请管理员检查该表的行权限配置。");
        }
        return parsed;
    }

    /** 归一化 SQL 文本用于往返一致性比较：忽略大小写与空白差异。 */
    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.toLowerCase().replaceAll("\\s+", "");
    }

    private String stripWherePrefix(String filter) {
        String trimmed = filter.trim();
        if (trimmed.length() >= 5 && trimmed.regionMatches(true, 0, "where", 0, 5)
                && (trimmed.length() == 5 || Character.isWhitespace(trimmed.charAt(5)))) {
            return trimmed.substring(5).trim();
        }
        return trimmed;
    }

    /**
     * 多表查询下把行过滤条件里的裸列名限定到目标表，避免列名歧义。
     */
    private String qualifyRowFilter(String rowFilter, Table table) {
        String qualifier = SqlTableCollector.aliasName(table) != null
                ? SqlTableCollector.aliasName(table) : table.getName();
        Expression expression = parseFilter(rowFilter);
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (column.getTable() == null || column.getTable().getName() == null
                        || column.getTable().getName().isEmpty()) {
                    column.setTable(new Table(qualifier));
                }
            }
        });
        return expression.toString();
    }

    /**
     * 行过滤条件注入 WHERE 时要求该层 JOIN 全部是内连接：
     * 对 LEFT / RIGHT / FULL JOIN 的空值侧，把过滤条件放进 WHERE 会改变连接语义
     * （等价于把外连接降级为内连接），因此按默认拒绝处理。
     */
    private void requireInnerJoinsOnly(PlainSelect plainSelect, Table table) {
        List<Join> joins = plainSelect.getJoins();
        if (joins == null) {
            return;
        }
        for (Join join : joins) {
            if (join == null) {
                continue;
            }
            if (join.isLeft() || join.isRight() || join.isFull() || join.isOuter()
                    || join.isSemi() || join.isApply()) {
                throw new SecurityException("权限不足：表 " + table.getName()
                        + " 配置了行级权限，但该查询使用了外连接 / 半连接，"
                        + "在 WHERE 中注入行过滤会改变连接语义，已拒绝执行。"
                        + "请改写为内连接，或改用 JDBC / Kyuubi 路径执行。");
            }
        }
    }

    // ==================== 列级裁剪 ====================

    private boolean pruneColumns(PlainSelect plainSelect, Table table, Set<String> allowedColumns) {
        List<SelectItem<?>> items = plainSelect.getSelectItems();
        if (items == null || items.isEmpty()) {
            return false;
        }
        Set<String> allowedLower = new TreeSet<>();
        for (String column : allowedColumns) {
            if (column != null && !column.isBlank()) {
                allowedLower.add(column.trim().toLowerCase());
            }
        }

        List<SelectItem<?>> kept = new ArrayList<>();
        boolean changed = false;
        for (SelectItem<?> item : items) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                for (String column : allowedLower) {
                    kept.add(new SelectItem<>(new Column(column)));
                }
                changed = true;
            } else if (expression instanceof Column) {
                String name = ((Column) expression).getColumnName();
                if (name != null && allowedLower.contains(name.toLowerCase())) {
                    kept.add(item);
                } else {
                    changed = true;
                }
            } else {
                for (String referenced : referencedColumns(expression)) {
                    if (!allowedLower.contains(referenced)) {
                        throw new SecurityException("权限不足：投影表达式引用了表 " + table.getName()
                                + " 上未授权的列 " + referenced + "，已拒绝执行。");
                    }
                }
                kept.add(item);
            }
        }
        if (kept.isEmpty()) {
            throw new SecurityException("权限不足：表 " + table.getName()
                    + " 的列级权限与查询投影列无交集，已拒绝执行。");
        }
        if (changed) {
            plainSelect.setSelectItems(kept);
        }
        return changed;
    }

    /** 收集表达式里引用的列名（小写）。 */
    private Set<String> referencedColumns(Expression expression) {
        Set<String> columns = new TreeSet<>();
        if (expression == null) {
            return columns;
        }
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (column.getColumnName() != null) {
                    columns.add(column.getColumnName().toLowerCase());
                }
            }
        });
        return columns;
    }

    // ==================== 子查询 ====================

    private boolean rewriteSubSelects(Expression expression, RewriteContext ctx) {
        if (expression == null) {
            return false;
        }
        final boolean[] changed = {false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect select) {
                if (select != null && select.getSelect() != null) {
                    changed[0] |= rewriteSelect(select.getSelect(), ctx, false);
                }
            }

            @Override
            public void visit(Select select) {
                if (select != null) {
                    changed[0] |= rewriteSelect(select, ctx, false);
                }
            }
        });
        return changed[0];
    }

    private String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    /** 一次改写请求的上下文。 */
    private static final class RewriteContext {
        private final String datasourceName;
        private final String defaultDatabase;

        private RewriteContext(String datasourceName, String defaultDatabase) {
            this.datasourceName = datasourceName;
            this.defaultDatabase = defaultDatabase;
        }
    }
}
