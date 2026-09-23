package com.bigdata.common.sql;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlTableCollector} 的行为验证。
 * <p>
 * 关键回归点：旧实现用 {@code SubJoin}/{@code SubSelect} 类名反射提取表引用，
 * 而 JSqlParser 4.9 已改名为 {@code ParenthesedFromItem}/{@code ParenthesedSelect}，
 * 导致 JOIN 右侧的表与子查询里的表被完全漏检（可用于绕过表权限）。
 * </p>
 */
class SqlTableCollectorTest {

    private List<String> readNames(String sql) throws Exception {
        Statement stmt = CCJSqlParserUtil.parse(sql);
        return SqlTableCollector.readTables((Select) stmt).stream()
                .map(SqlTableCollector::qualifiedName)
                .collect(Collectors.toList());
    }

    @Test
    void collectsJoinedTables() throws Exception {
        List<String> names = readNames("SELECT a.id FROM db1.orders a JOIN db2.customers b ON a.cid = b.id");
        assertEquals(List.of("db1.orders", "db2.customers"), names);
    }

    @Test
    void collectsSubQueryInFrom() throws Exception {
        List<String> names = readNames("SELECT * FROM (SELECT * FROM secret_t) x");
        assertEquals(List.of("secret_t"), names);
    }

    @Test
    void collectsSubQueryInWhere() throws Exception {
        List<String> names = readNames("SELECT * FROM a WHERE id IN (SELECT id FROM secret_t)");
        assertEquals(List.of("a", "secret_t"), names);
    }

    @Test
    void collectsCteAndSetOperationBranches() throws Exception {
        List<String> names = readNames("WITH c AS (SELECT * FROM t1) SELECT * FROM c UNION ALL SELECT * FROM t2");
        assertEquals(List.of("t1", "c", "t2"), names);
    }

    @Test
    void collectsInsertSourceTables() throws Exception {
        Statement stmt = CCJSqlParserUtil.parse("INSERT INTO target_t SELECT * FROM source_t");
        List<String> names = SqlTableCollector.statementTables(stmt).stream()
                .map(SqlTableCollector::qualifiedName)
                .collect(Collectors.toList());
        assertTrue(names.contains("target_t"), "写入目标应被收集: " + names);
        assertTrue(names.contains("source_t"), "INSERT ... SELECT 的来源表应被收集: " + names);
    }

    @Test
    void exposesAliasAndDatabase() throws Exception {
        Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM db1.orders o");
        Table table = SqlTableCollector.readTables((Select) stmt).get(0);
        assertEquals("db1", SqlTableCollector.databaseName(table));
        assertEquals("o", SqlTableCollector.aliasName(table));
        assertEquals("db1.orders", SqlTableCollector.qualifiedName(table));
    }

    @Test
    void levelTablesIgnoresDerivedTableContent() throws Exception {
        Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM (SELECT * FROM t) x JOIN u ON x.a = u.a");
        List<String> level = SqlTableCollector.levelTables(
                        (net.sf.jsqlparser.statement.select.PlainSelect) stmt).stream()
                .map(SqlTableCollector::qualifiedName)
                .collect(Collectors.toList());
        assertEquals(List.of("u"), level);
    }
}
