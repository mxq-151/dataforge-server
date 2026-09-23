package com.bigdata.modules.quality.sql;

import com.bigdata.modules.quality.enums.QualityCompareMode;
import com.bigdata.modules.quality.enums.QualityRuleType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 7 类内置校验模板的**规范定义**（SQL 模板 + 占位符说明 + 默认判定口径）。
 *
 * <p>为什么模板正文放在代码里而不是让 dq_rule_template 表承载：
 * <ul>
 *   <li>模板正文决定「校验 SQL 长什么样」，任何改动都必须跟着 {@link DqSqlBuilder} 的渲染逻辑一起走；
 *       分散到 4 份建库脚本 + 2 份迁移脚本里，改一处漏三处的概率远高于改一个枚举；</li>
 *   <li>建库脚本只负责把 7 条<b>目录行</b>（template_code == rule_type）种进 dq_rule_template，
 *       供界面显示与 template_id 引用；模板正文由本类在返回时补齐。</li>
 * </ul>
 * </p>
 *
 * <p>{@code template_code} 与 {@link QualityRuleType} 的编码同名，这是「按模板建规则」
 * 能反查到归属类型的唯一依据 —— {@code QualityRuleTypeContractTest} 会断言这一点。</p>
 *
 * <h3>阈值口径落在契约既有字段上</h3>
 * <p>前端 {@code QualityRuleVO} 冻结的字段里已经有 {@code expectedValue} /
 * {@code warnThreshold} / {@code errorThreshold} 三个阈值位，因此本模块<b>不再另造</b>
 * 「阈值参数」之类的字段，各规则类型的阈值一律这样映射：</p>
 * <ul>
 *   <li>计<a>异常数</a>的规则（主键唯一性 / 非空 / 值域）：{@code expectedValue} = 允许的异常条数，
 *       默认 {@code 0}；实际值 = 异常行数（主键唯一性取「重复键组数」）。</li>
 *   <li>算<a>涨跌幅</a>的规则（行数波动 / 行数环比）：{@code warnThreshold} / {@code errorThreshold}
 *       = 允许的涨跌幅上限（百分数，可带 {@code %}），实际值 = 涨跌幅绝对值。</li>
 *   <li>比<a>相对差异</a>的规则（上下游对账 / 口径一致性）：{@code warnThreshold} /
 *       {@code errorThreshold} = 允许的相对差异上限（百分数），实际值 = 相对差异绝对值。</li>
 * </ul>
 * <p>值域规则的合法取值写在 {@code expectedValue}：{@code [0,100]} 视为数值区间（闭区间），
 * 其余视为枚举字典（英文逗号分隔）。</p>
 *
 * <h3>SQL 占位符约定（全模板统一）</h3>
 * <table>
 *   <tr><td>{@code ${table}}</td><td>已加引用符的「库.表」限定名</td></tr>
 *   <tr><td>{@code ${refTable}}</td><td>对照表限定名（跨表规则）</td></tr>
 *   <tr><td>{@code ${column}} / {@code ${refColumn}}</td><td>已加引用符的字段名</td></tr>
 *   <tr><td>{@code ${charType}}</td><td>该方言下「转字符串」的类型名（CHAR / VARCHAR / STRING）</td></tr>
 *   <tr><td>{@code ${partitionWhere}}</td><td>分区过滤，展开为 {@code WHERE (...)} 或空串</td></tr>
 *   <tr><td>{@code ${partition}}</td><td>分区过滤，展开为 {@code  AND (...)} 或空串</td></tr>
 *   <tr><td>{@code ${mainAgg}} / {@code ${refAgg}}</td><td>聚合表达式（{@code COUNT(*)} 或 {@code SUM(列)}）</td></tr>
 *   <tr><td>{@code ${predicate}}</td><td>值域规则由 {@code expectedValue} 推出的判定谓词</td></tr>
 * </table>
 */
public final class DqBuiltinTemplates {

    private DqBuiltinTemplates() {
    }

    /** 行数波动：基线窗口天数参数名 */
    public static final String PARAM_BASELINE_DAYS = "baselineDays";
    /** 行数环比：期长天数参数名 */
    public static final String PARAM_PERIOD_DAYS = "periodDays";
    /** 对账 / 一致性：聚合口径参数名（count / sum） */
    public static final String PARAM_AGG_MODE = "aggMode";

    /** 默认基线窗口（天）：近 7 天 */
    public static final int DEFAULT_BASELINE_DAYS = 7;

    /**
     * 一个内置模板的完整定义。
     *
     * @param templateCode   模板编码（== 规则类型编码）
     * @param templateName   模板名（== 规则类型的名称，与提示词逐字一致）
     * @param ruleType       归属的规则类型
     * @param sqlTemplate    SQL 模板正文
     * @param paramSchema    占位符说明（JSON 数组字符串，供界面提示；无参模板为 {@code []}）
     * @param fieldTypes     适用字段类型（逗号分隔，空表示不限）
     * @param metricKind     实际值的语义（决定取回结果后怎么算「实际值」）
     * @param compareMode    默认比较方式
     * @param expectedValue  默认期望值（可空）
     * @param defaultParams  可选占位符参数的默认取值
     */
    public record Definition(
            String templateCode,
            String templateName,
            QualityRuleType ruleType,
            String sqlTemplate,
            String paramSchema,
            String fieldTypes,
            DqSqlBuilder.MetricKind metricKind,
            QualityCompareMode compareMode,
            String expectedValue,
            Map<String, String> defaultParams) {
    }

    private static final Map<String, Definition> BY_CODE = new LinkedHashMap<>();

    static {
        for (Definition definition : build()) {
            BY_CODE.put(definition.templateCode(), definition);
        }
    }

    public static List<Definition> all() {
        return List.copyOf(BY_CODE.values());
    }

    public static Definition of(String templateCode) {
        return templateCode == null ? null : BY_CODE.get(templateCode.trim());
    }

    public static Definition of(QualityRuleType type) {
        return type == null ? null : of(type.getCode());
    }

    /** 规则类型编码集合（即内置模板编码集合），供自检断言 */
    public static List<String> templateCodes() {
        return Arrays.stream(QualityRuleType.values()).map(QualityRuleType::getCode).collect(Collectors.toList());
    }

    private static List<Definition> build() {
        return List.of(
                // ── 1. 主键唯一性 ────────────────────────────────────────────
                new Definition(
                        QualityRuleType.PRIMARY_KEY_UNIQUE.getCode(),
                        QualityRuleType.PRIMARY_KEY_UNIQUE.getLabel(),
                        QualityRuleType.PRIMARY_KEY_UNIQUE,
                        """
                        -- 实际值 = 重复键「组数」（一组重复唯一键算一个问题点，不是重复行数）
                        SELECT COUNT(*) FROM (
                          SELECT ${column} FROM ${table}${partitionWhere} GROUP BY ${column} HAVING COUNT(*) > 1
                        ) df_dup""",
                        "[]",
                        "",
                        DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT,
                        QualityCompareMode.LTE,
                        "0",
                        Map.of()),

                // ── 2. 非空 ───────────────────────────────────────────────────
                new Definition(
                        QualityRuleType.NOT_NULL.getCode(),
                        QualityRuleType.NOT_NULL.getLabel(),
                        QualityRuleType.NOT_NULL,
                        """
                        -- 实际值 = 空值或空串行数
                        -- 空串判定统一走 TRIM(CAST(列 AS 字符串类型))：PG 不允许数值列直接与 '' 比较
                        SELECT COUNT(*) FROM ${table} WHERE (${column} IS NULL OR TRIM(CAST(${column} AS ${charType})) = '')${partition}""",
                        "[]",
                        "",
                        DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT,
                        QualityCompareMode.LTE,
                        "0",
                        Map.of()),

                // ── 3. 值域 ───────────────────────────────────────────────────
                new Definition(
                        QualityRuleType.VALUE_RANGE.getCode(),
                        QualityRuleType.VALUE_RANGE.getLabel(),
                        QualityRuleType.VALUE_RANGE,
                        """
                        -- 实际值 = 越界行数（NULL 不计入，空值问题归「非空」规则，避免两条规则重复计问题）
                        -- 判定谓词由 expectedValue 推出：
                        --   区间模式 expectedValue = [0,100]（闭区间）→ 列 < 0 OR 列 > 100
                        --   字典模式 expectedValue = 1,2,3        → TRIM(CAST(列 AS 字符串)) NOT IN ('1','2','3')
                        SELECT COUNT(*) FROM ${table} WHERE ${column} IS NOT NULL AND (${predicate})${partition}""",
                        paramSchema(
                                param("expectedValue", "允许取值", "0",
                                        "区间写 [下界,上界]（闭区间）；枚举写英文逗号分隔的取值")),
                        "",
                        DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT,
                        QualityCompareMode.LTE,
                        "0",
                        Map.of()),

                // ── 4. 行数波动 ───────────────────────────────────────────────
                new Definition(
                        QualityRuleType.ROW_COUNT_FLUCTUATION.getCode(),
                        QualityRuleType.ROW_COUNT_FLUCTUATION.getLabel(),
                        QualityRuleType.ROW_COUNT_FLUCTUATION,
                        """
                        -- 实际值 = 当期行数；涨跌幅由平台按历史基线算出（见 DqRuleEvaluator）
                        SELECT COUNT(*) FROM ${table}${partitionWhere}""",
                        paramSchema(
                                param(PARAM_BASELINE_DAYS, "基线窗口(天)", String.valueOf(DEFAULT_BASELINE_DAYS),
                                        "取该规则最近 N 次成功运行行数的均值作为基线")),
                        "",
                        DqSqlBuilder.MetricKind.DEVIATION_PERCENT,
                        QualityCompareMode.LTE,
                        null,
                        Map.of(PARAM_BASELINE_DAYS, String.valueOf(DEFAULT_BASELINE_DAYS))),

                // ── 5. 行数环比 ───────────────────────────────────────────────
                new Definition(
                        QualityRuleType.ROW_COUNT_MOM.getCode(),
                        QualityRuleType.ROW_COUNT_MOM.getLabel(),
                        QualityRuleType.ROW_COUNT_MOM,
                        """
                        -- 实际值 = 当期行数；环比涨跌幅由平台与「上一期」比较后算出
                        SELECT COUNT(*) FROM ${table}${partitionWhere}""",
                        paramSchema(
                                param(PARAM_PERIOD_DAYS, "期长(天)", "1", "1=日环比，7=周环比")),
                        "",
                        DqSqlBuilder.MetricKind.DEVIATION_PERCENT,
                        QualityCompareMode.LTE,
                        null,
                        Map.of(PARAM_PERIOD_DAYS, "1")),

                // ── 6. 上下游对账 ─────────────────────────────────────────────
                new Definition(
                        QualityRuleType.UPSTREAM_DOWNSTREAM_RECON.getCode(),
                        QualityRuleType.UPSTREAM_DOWNSTREAM_RECON.getLabel(),
                        QualityRuleType.UPSTREAM_DOWNSTREAM_RECON,
                        """
                        -- 取两列：主表口径值、对照表口径值；相对差异由平台计算
                        -- 记录数对账 aggMode=count（默认）；金额对账 aggMode=sum 并指定字段
                        SELECT (SELECT ${mainAgg} FROM ${table}${partitionWhere}) AS main_value,
                               (SELECT ${refAgg} FROM ${refTable}${refPartitionWhere}) AS ref_value""",
                        paramSchema(
                                param(PARAM_AGG_MODE, "对账口径", "count", "count=记录数 / sum=字段求和；sum 时按规则配置的字段求和")),
                        "",
                        DqSqlBuilder.MetricKind.DIFF_PERCENT,
                        QualityCompareMode.LTE,
                        null,
                        Map.of(PARAM_AGG_MODE, "count")),

                // ── 7. 口径一致性 ─────────────────────────────────────────────
                new Definition(
                        QualityRuleType.METRIC_CONSISTENCY.getCode(),
                        QualityRuleType.METRIC_CONSISTENCY.getLabel(),
                        QualityRuleType.METRIC_CONSISTENCY,
                        """
                        -- 取两列：两处口径的指标值；相对差异由平台计算
                        -- 配置了字段即按 SUM(字段) 比对；未配置字段时退化为 COUNT(*)
                        SELECT (SELECT ${mainAgg} FROM ${table}${partitionWhere}) AS main_value,
                               (SELECT ${refAgg} FROM ${refTable}${refPartitionWhere}) AS ref_value""",
                        paramSchema(
                                param(PARAM_AGG_MODE, "比对口径", "sum", "sum=按字段求和 / count=记录数")),
                        "",
                        DqSqlBuilder.MetricKind.DIFF_PERCENT,
                        QualityCompareMode.LTE,
                        null,
                        Map.of(PARAM_AGG_MODE, "sum")));
    }

    // ==================================================================
    // paramSchema 构造
    // ==================================================================

    /** 无占位符参数的模板：空 JSON 数组，界面据此不渲染动态提示 */
    private static String paramSchema(ParamDef... params) {
        if (params.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < params.length; i++) {
            ParamDef p = params[i];
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"key\":\"").append(escapeJson(p.key()))
                    .append("\",\"label\":\"").append(escapeJson(p.label()))
                    .append("\",\"defaultValue\":\"").append(escapeJson(p.defaultValue()))
                    .append("\",\"hint\":\"").append(escapeJson(p.hint()))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ParamDef param(String key, String label, String defaultValue, String hint) {
        return new ParamDef(key, label, defaultValue, hint);
    }

    private record ParamDef(String key, String label, String defaultValue, String hint) {
    }
}
