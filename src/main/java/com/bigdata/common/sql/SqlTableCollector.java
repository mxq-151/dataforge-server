package com.bigdata.common.sql;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * SQL 表引用收集工具（JSqlParser 4.9 AST）。
 * <p>
 * 用于权限校验与 SQL 改写两条链路。相比「只看 fromItem」的旧实现，本工具会覆盖：
 * <ul>
 *   <li>JOIN 右侧的表（{@code PlainSelect.getJoins()}）</li>
 *   <li>括号包裹的 FROM 子树（{@link ParenthesedFromItem}）</li>
 *   <li>FROM 子句中的派生表（{@link ParenthesedSelect}）</li>
 *   <li>WHERE / HAVING / JOIN ON / 投影列中的子查询</li>
 *   <li>UNION 等集合运算的各分支、WITH 子句的 CTE 定义</li>
 * </ul>
 * 旧实现依赖 {@code SubJoin}/{@code SubSelect} 类名反射，而 JSqlParser 4.9 已把这两个类
 * 改名为 {@code ParenthesedFromItem}/{@code ParenthesedSelect}，导致 JOIN 与子查询中的表
 * 完全漏检——本工具修复该缺口。
 * </p>
 */
public final class SqlTableCollector {

    private SqlTableCollector() {
    }

    /**
     * 收集单层 SELECT 的表引用（FROM 项 + 各 JOIN 右侧），保持出现顺序。
     */
    public static List<Table> levelTables(PlainSelect plainSelect) {
        List<Table> tables = new ArrayList<>();
        collectLevel(plainSelect.getFromItem(), tables);
        List<Join> joins = plainSelect.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                collectLevel(join != null ? join.getRightItem() : null, tables);
            }
        }
        return tables;
    }

    private static void collectLevel(FromItem item, List<Table> tables) {
        List<FromItem> leaves = new ArrayList<>();
        leaves(item, leaves);
        for (FromItem leaf : leaves) {
            if (leaf instanceof Table) {
                tables.add((Table) leaf);
            }
        }
    }

    /**
     * 把一个 FROM 项展开为叶子节点：{@link Table}、派生表、表函数、LATERAL 等。
     * 括号包裹的 FROM 子树会被递归展开并保持顺序。
     */
    public static void leaves(FromItem item, List<FromItem> out) {
        if (item == null) {
            return;
        }
        if (item instanceof ParenthesedFromItem) {
            ParenthesedFromItem pfi = (ParenthesedFromItem) item;
            leaves(pfi.getFromItem(), out);
            List<Join> joins = pfi.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    leaves(join != null ? join.getRightItem() : null, out);
                }
            }
            return;
        }
        out.add(item);
    }

    /**
     * 递归收集一条 SELECT 读到的所有表（含各层子查询、集合运算分支、CTE）。
     * <p>返回结果去重（按 {@code schema.name} 与别名组合），保持首次出现顺序。</p>
     */
    public static List<Table> readTables(Select select) {
        List<Table> out = new ArrayList<>();
        collectRead(select, out);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Table> deduped = new ArrayList<>();
        for (Table t : out) {
            String key = (databaseName(t) != null ? databaseName(t) + "." : "") + t.getName()
                    + (aliasName(t) != null ? "@" + aliasName(t) : "");
            if (seen.add(key)) {
                deduped.add(t);
            }
        }
        return deduped;
    }

    private static void collectRead(Select select, List<Table> out) {
        if (select == null) {
            return;
        }
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem wi : withItems) {
                if (wi != null && wi.getSelect() != null) {
                    collectRead(wi.getSelect(), out);
                }
            }
        }
        if (select instanceof PlainSelect) {
            PlainSelect ps = (PlainSelect) select;
            collectFromLeaves(ps.getFromItem(), ps.getJoins(), out);
            collectSubSelects(ps.getWhere(), out);
            collectSubSelects(ps.getHaving(), out);
            List<SelectItem<?>> items = ps.getSelectItems();
            if (items != null) {
                for (SelectItem<?> item : items) {
                    if (item != null) {
                        collectSubSelects(item.getExpression(), out);
                    }
                }
            }
            List<Join> joins = ps.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    if (join != null) {
                        collectSubSelects(join.getOnExpression(), out);
                    }
                }
            }
        } else if (select instanceof SetOperationList) {
            List<Select> branches = ((SetOperationList) select).getSelects();
            if (branches != null) {
                for (Select branch : branches) {
                    collectRead(branch, out);
                }
            }
        } else if (select instanceof ParenthesedSelect) {
            collectRead(((ParenthesedSelect) select).getSelect(), out);
        }
    }

    /** FROM 子树中的表与派生表：表直接收集，派生表继续递归。 */
    private static void collectFromLeaves(FromItem fromItem, List<Join> joins, List<Table> out) {
        List<FromItem> leaves = new ArrayList<>();
        leaves(fromItem, leaves);
        if (joins != null) {
            for (Join join : joins) {
                leaves(join != null ? join.getRightItem() : null, leaves);
            }
        }
        for (FromItem leaf : leaves) {
            if (leaf instanceof Table) {
                out.add((Table) leaf);
            } else if (leaf instanceof ParenthesedSelect) {
                Select inner = ((ParenthesedSelect) leaf).getSelect();
                if (inner != null) {
                    collectRead(inner, out);
                }
            }
        }
    }

    /** 用表达式访问器捞取 WHERE / HAVING / ON / 投影列里出现的子查询。 */
    private static void collectSubSelects(Expression expression, final List<Table> out) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect select) {
                collectRead(select.getSelect(), out);
            }

            @Override
            public void visit(Select select) {
                collectRead(select, out);
            }
        });
    }

    /**
     * 收集整条语句涉及的表：
     * <ul>
     *   <li>SELECT：全部读表</li>
     *   <li>INSERT：写入目标 + 来源 SELECT 的读表</li>
     *   <li>UPDATE / DELETE：写入目标 + JOIN 关联表</li>
     * </ul>
     */
    public static List<Table> statementTables(Statement statement) {
        List<Table> out = new ArrayList<>();
        if (statement instanceof Select) {
            out.addAll(readTables((Select) statement));
        } else if (statement instanceof Insert) {
            Insert insert = (Insert) statement;
            if (insert.getTable() != null) {
                out.add(insert.getTable());
            }
            if (insert.getSelect() != null) {
                out.addAll(readTables(insert.getSelect()));
            }
        } else if (statement instanceof Update) {
            Update update = (Update) statement;
            if (update.getTable() != null) {
                out.add(update.getTable());
            }
            out.addAll(joinTables(update.getStartJoins()));
            out.addAll(joinTables(update.getJoins()));
            if (update.getSelect() != null) {
                out.addAll(readTables(update.getSelect()));
            }
        } else if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            if (delete.getTable() != null) {
                out.add(delete.getTable());
            }
            if (delete.getTables() != null) {
                out.addAll(delete.getTables());
            }
            out.addAll(joinTables(delete.getJoins()));
        }
        return out;
    }

    private static List<Table> joinTables(List<Join> joins) {
        List<Table> out = new ArrayList<>();
        if (joins == null) {
            return out;
        }
        for (Join join : joins) {
            collectLevel(join != null ? join.getRightItem() : null, out);
        }
        return out;
    }

    /** 表所属库/模式名，可能为 null。 */
    public static String databaseName(Table table) {
        if (table == null) {
            return null;
        }
        if (table.getSchemaName() != null && !table.getSchemaName().isEmpty()) {
            return table.getSchemaName();
        }
        if (table.getDatabase() != null) {
            return table.getDatabase().getDatabaseName();
        }
        return null;
    }

    /** 表别名（不含 AS），无别名时返回 null。 */
    public static String aliasName(Table table) {
        if (table == null || table.getAlias() == null) {
            return null;
        }
        return table.getAlias().getName();
    }

    /** 权限校验/日志用的规范化全名。 */
    public static String qualifiedName(Table table) {
        String db = databaseName(table);
        return (db != null && !db.isEmpty() ? db + "." : "") + table.getName();
    }
}
