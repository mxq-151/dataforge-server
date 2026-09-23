package com.bigdata.modules.quality.sql;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.enums.DqDialect;
import com.bigdata.modules.quality.enums.QualityRuleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 SQL 渲染的「规则类型 × 方言」矩阵测试。
 *
 * <p>这是质量模块最关键的一道闸：渲染出来的 SQL 会被<b>真实下发</b>到目标库。
 * 一旦某个 (规则类型, 方言) 组合渲染出语法不成立的 SQL，后果不是「报告少一条」，
 * 而是任务执行报错或（更糟）判出错误的通过/失败结论。</p>
 *
 * <p>因此这里对 7 类 × 3 方言的 21 个组合逐一渲染并断言四条不变量：</p>
 * <ol>
 *   <li>渲染结果是一条 SELECT（不含分号），可直接下发；</li>
 *   <li>没有占位符残留（残留说明模板与占位符集合脱节）；</li>
 *   <li>表名按该方言的引用符包裹（PG 用双引号、MySQL/Hive 用反引号）；</li>
 *   <li>取回列数与度量语义与模板定义一致（判定逻辑依赖它）。</li>
 * </ol>
 *
 * <p>另外单独覆盖「注入面」：库表字段名、分区表达式、自定义模板
 * 三处都是外部可控输入，必须 fail-close 而不是「拼进去试试」。</p>
 */
@DisplayName("校验 SQL 渲染 —— 规则类型 × 方言矩阵")
class DqSqlBuilderMatrixTest {

    private static final String DB = "dw";
    private static final String TABLE = "dwd_order_detail_d";
    private static final String COLUMN = "order_id";
    private static final String REF_DB = "dw";
    private static final String REF_TABLE = "dws_order_sum_d";

    private static final List<DqDialect> DIALECTS = List.of(
            DqDialect.MYSQL, DqDialect.POSTGRESQL, DqDialect.HIVE);

    /** 按规则类型构造一个「配置完整」的规则上下文 */
    private static DqSqlBuilder.RuleContext context(QualityRuleType type, DqDialect dialect) {
        Map<String, String> params = new LinkedHashMap<>();
        params.putAll(DqRuleEvaluator.defaultsFor(type.getCode()));

        DqSqlBuilder.RuleContext ctx = new DqSqlBuilder.RuleContext()
                .setRuleType(type)
                .setDialect(dialect)
                .setDatabaseName(DB)
                .setTableName(TABLE)
                .setColumnName(COLUMN)
                .setRefDatabaseName(REF_DB)
                .setRefTableName(REF_TABLE)
                .setRefColumnName(COLUMN)
                .setParams(params)
                .setBatchDate(LocalDate.of(2026, 9, 17));

        // 值域规则必须在期望值里写明允许取值，否则渲染阶段就会拒绝
        if (type == QualityRuleType.VALUE_RANGE) {
            ctx.setExpectedValue("[0,100]");
        }
        return ctx;
    }

    @Nested
    @DisplayName("21 个组合均可直接下发")
    class Render {

        @Test
        @DisplayName("每类规则在每种方言下都能渲染出可直接下发的单条 SELECT")
        void rendersSingleSelectStatement() {
            for (QualityRuleType type : QualityRuleType.values()) {
                for (DqDialect dialect : DIALECTS) {
                    DqSqlBuilder.CompiledSql compiled = DqSqlBuilder.compile(context(type, dialect));
                    String label = type.getCode() + " / " + dialect;

                    assertNotNull(compiled.sql(), label + " 未渲染出 SQL");
                    assertTrue(DqSqlBuilder.isSingleSelectStatement(compiled.sql()),
                            label + " 不是可直接下发的单条 SELECT：\n" + compiled.sql());
                    assertFalse(compiled.sql().contains("${"), label + " 存在未替换的占位符：\n" + compiled.sql());
                    assertTrue(compiled.checkObject() != null && !compiled.checkObject().isBlank(),
                            label + " 缺少校验对象描述（结果留痕需要它）");

                    String quote = dialect.getQuote();
                    assertTrue(compiled.sql().contains(quote + TABLE + quote),
                            label + " 表名未按方言引用符包裹：\n" + compiled.sql());
                }
            }
        }

        @Test
        @DisplayName("取回列数与度量语义跟模板定义一致")
        void metricKindAndColumnCountMatchTemplate() {
            for (QualityRuleType type : QualityRuleType.values()) {
                DqBuiltinTemplates.Definition definition = DqBuiltinTemplates.of(type);
                assertNotNull(definition, type.getCode() + " 缺少内置模板");

                for (DqDialect dialect : DIALECTS) {
                    DqSqlBuilder.CompiledSql compiled = DqSqlBuilder.compile(context(type, dialect));
                    String label = type.getCode() + " / " + dialect;

                    assertEquals(definition.metricKind(), compiled.metricKind(),
                            label + " 度量语义与模板定义不一致");
                    int expectedColumns = definition.metricKind() == DqSqlBuilder.MetricKind.DIFF_PERCENT ? 2 : 1;
                    assertEquals(expectedColumns, compiled.valueColumnCount(),
                            label + " 取回列数应为 " + expectedColumns);
                }
            }
        }

        @Test
        @DisplayName("跨表两类的逻辑表名也是方言引用符包裹的限定名")
        void crossTableRulesQualifyBothSides() {
            for (QualityRuleType type : List.of(QualityRuleType.UPSTREAM_DOWNSTREAM_RECON,
                    QualityRuleType.METRIC_CONSISTENCY)) {
                for (DqDialect dialect : DIALECTS) {
                    String sql = DqSqlBuilder.compile(context(type, dialect)).sql();
                    String quote = dialect.getQuote();
                    assertTrue(sql.contains(quote + DB + quote + "." + quote + TABLE + quote),
                            type.getCode() + " 缺少主表限定名：\n" + sql);
                    assertTrue(sql.contains(quote + REF_DB + quote + "." + quote + REF_TABLE + quote),
                            type.getCode() + " 缺少对照表限定名：\n" + sql);
                }
            }
        }

        @Test
        @DisplayName("分区表达式按批次日期展开，并统一补上日期字面量引号")
        void partitionExpressionExpandsByBatchDate() {
            DqSqlBuilder.RuleContext ctx = context(QualityRuleType.ROW_COUNT_MOM, DqDialect.POSTGRESQL)
                    .setPartitionExpr("dt = ${yyyyMMdd}");
            String sql = DqSqlBuilder.compile(ctx).sql();
            // PostgreSQL 下 dt=20260917 会因「整数与 varchar 比较」直接报错，必须补引号。
            // 补引号逻辑按正则就地插入（不重排空格），因此这里比较去空白后的结果。
            String compact = sql.replaceAll("\\s+", "");
            assertTrue(compact.contains("dt='20260917'"),
                    "分区日期占位符未展开或未补引号：\n" + sql);
        }

        @Test
        @DisplayName("分区表达式支持按批次日期做偏移（-1d / +2h）")
        void partitionExpressionSupportsOffsets() {
            String sql = DqSqlBuilder.compile(
                    context(QualityRuleType.ROW_COUNT_MOM, DqDialect.MYSQL)
                            .setPartitionExpr("dt = ${yyyyMMdd-1d}")).sql();
            assertTrue(sql.contains("20260916"), "未按 -1d 偏移展开：\n" + sql);
        }
    }

    @Nested
    @DisplayName("外部可控输入必须 fail-close")
    class InjectionGuard {

        @Test
        @DisplayName("含非法字符的库表字段名被拒绝")
        void rejectsUnsafeIdentifiers() {
            assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.ROW_COUNT_MOM, DqDialect.MYSQL)
                            .setTableName("orders; DROP TABLE users")));
            assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.NOT_NULL, DqDialect.MYSQL)
                            .setColumnName("id` , (SELECT 1)")));
            assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.ROW_COUNT_MOM, DqDialect.POSTGRESQL)
                            .setDatabaseName("dw\" or 1=1")));
        }

        @Test
        @DisplayName("字段级规则缺字段、跨表规则缺对照表时被拒绝（下发前最后一道闸）")
        void rejectsMissingRequiredFields() {
            // 若只靠占位符本身的可选性，字段级规则缺字段会渲染出
            // 「WHERE (  IS NULL OR ...)」这种语法不成立的残句，直到下发才报错
            BusinessException missingColumn = assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.NOT_NULL, DqDialect.MYSQL).setColumnName(null)));
            assertTrue(missingColumn.getMessage().contains("字段级规则"),
                    "拒绝原因应指明缺少字段，实际: " + missingColumn.getMessage());

            BusinessException missingRef = assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.UPSTREAM_DOWNSTREAM_RECON, DqDialect.MYSQL)
                            .setRefTableName("  ")));
            assertTrue(missingRef.getMessage().contains("跨表规则"),
                    "拒绝原因应指明缺少对照表，实际: " + missingRef.getMessage());
        }

        @Test
        @DisplayName("分区表达式里的语句分隔符、注释符与子查询关键字被拒绝")
        void rejectsDangerousPartitionExpression() {
            for (String expr : List.of(
                    "dt = '20260917'; DROP TABLE t",
                    "dt = 1 -- 备注",
                    "dt = 1 /* 块注释 */",
                    "dt IN (SELECT dt FROM other)")) {
                assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                        context(QualityRuleType.ROW_COUNT_MOM, DqDialect.MYSQL).setPartitionExpr(expr)),
                        "分区表达式未被拒绝: " + expr);
            }
        }

        @Test
        @DisplayName("值域规则缺期望值、区间下界大于上界时被拒绝")
        void rejectsInvalidValueRange() {
            assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.VALUE_RANGE, DqDialect.MYSQL).setExpectedValue(null)));
            assertThrows(BusinessException.class, () -> DqSqlBuilder.compile(
                    context(QualityRuleType.VALUE_RANGE, DqDialect.MYSQL).setExpectedValue("[100,0]")));
        }

        @Test
        @DisplayName("自定义模板：跨表两类不支持、占位符残留被拒绝")
        void rejectsCustomTemplateMisuse() {
            // 跨表两类的结果契约是「两列」，自定义模板无法保证列数语义，必须拒绝
            BusinessException pair = assertThrows(BusinessException.class, () -> DqSqlBuilder.compileCustom(
                    "SELECT COUNT(*) FROM ${table}",
                    context(QualityRuleType.METRIC_CONSISTENCY, DqDialect.MYSQL)));
            assertTrue(pair.getMessage().contains("跨表"), "拒绝原因应说明跨表不支持，实际: " + pair.getMessage());

            // 单表规则的自定义模板引用了未提供的占位符 → 拒绝而不是留下 ${xxx} 下发
            BusinessException unknown = assertThrows(BusinessException.class, () -> DqSqlBuilder.compileCustom(
                    "SELECT COUNT(*) FROM ${table} WHERE ${notDefined} = 1",
                    context(QualityRuleType.ROW_COUNT_MOM, DqDialect.MYSQL)));
            assertNotNull(unknown.getMessage());
        }
    }
}
