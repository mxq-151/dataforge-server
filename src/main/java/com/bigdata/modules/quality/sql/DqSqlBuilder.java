package com.bigdata.modules.quality.sql;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.enums.DqDialect;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 SQL 渲染器 —— 把「规则配置」编译成一条可在目标引擎上真实执行的校验 SQL。
 *
 * <p>纯函数、无 Spring 依赖：所有输入显式传入，因此规则类型 × 方言的矩阵可以被单测穷举。</p>
 *
 * <h3>为什么必须由平台渲染，而不是让用户写整条 SQL</h3>
 * <p>校验 SQL 会被真实下发到目标引擎执行，库名/表名/字段名/分区条件都来自界面输入。
 * 若直接把用户拼好的 SQL 下发，等于把「任意 SQL 执行」的能力开放给任何能配置规则的人。
 * 因此这里做三件事：</p>
 * <ol>
 *   <li><b>标识符白名单化</b>：库/表/字段名逐个校验字符集上限，再按方言加引用符 ——
 *       既挡注入，又避免保留字（order / group）导致语法失败；</li>
 *   <li><b>占位符封闭</b>：模板渲染后若仍残留 {@code ${...}}，说明参数没给全，
 *       直接抛错而不是把半成品 SQL 下发（fail-close）；</li>
 *   <li><b>分区条件收窄</b>：分区表达式允许带 `${yyyyMMdd}` 之类的日期占位符，
 *       但不允许出现子查询/集合运算/语句分隔符。</li>
 * </ol>
 *
 * <h3>「实际值」的语义</h3>
 * <p>校验 SQL 只负责把数取回来（1 列或 2 列），「实际值」的算法收敛在
 * {@link MetricKind} 上，由 {@code DqRuleEvaluator} 统一判定 ——
 * 这样「SQL 长什么样」与「怎么算通过」两件事不会互相污染。</p>
 */
public final class DqSqlBuilder {

    /**
     * 实际值的语义 —— 决定取回结果后怎么折算成「实际值」。
     *
     * <p>刻意只保留三种：任何新规则类型都应该能被归入其中之一，
     * 否则说明「实际值」的口径还没有想清楚。</p>
     */
    public enum MetricKind {
        /** SQL 返回 1 列 = 异常条数（重复组数 / 空值行数 / 越界行数），越小越好 */
        ABNORMAL_ROW_COUNT,

        /** SQL 返回 1 列 = 当期行数，实际值 = 相对基线的涨跌幅绝对值（%） */
        DEVIATION_PERCENT,

        /** SQL 返回 2 列 = [主表口径值, 对照表口径值]，实际值 = 相对差异绝对值（%） */
        DIFF_PERCENT
    }

    /**
     * 编译结果。
     *
     * @param sql              可直接下发执行的校验 SQL（已去掉注释）
     * @param checkObject      校验对象展示串，对应契约里的 checkObject
     * @param metricKind       实际值语义
     * @param valueColumnCount SQL 返回的列数（1 或 2）
     * @param placeholders     本次渲染用到的占位符取值，便于排查与审计
     */
    public record CompiledSql(
            String sql,
            String checkObject,
            MetricKind metricKind,
            int valueColumnCount,
            Map<String, String> placeholders) {

        public boolean twoColumns() {
            return valueColumnCount >= 2;
        }
    }

    /**
     * 渲染上下文 —— 与实体解耦，便于单测直接构造。
     *
     * <p>字段命名刻意沿用 dq_rule 的语义：跨表规则的对照侧统一以 {@code ref} 前缀区分。</p>
     */
    public static final class RuleContext {
        private QualityRuleType ruleType;
        private DqDialect dialect = DqDialect.MYSQL;
        private String databaseName;
        private String tableName;
        private String columnName;
        private String partitionExpr;
        private String refDatabaseName;
        private String refTableName;
        private String refColumnName;
        private String expectedValue;
        private String refExpectedValue;
        private Map<String, String> params = Map.of();
        private LocalDate batchDate = LocalDate.now();

        public QualityRuleType getRuleType() {
            return ruleType;
        }

        public RuleContext setRuleType(QualityRuleType ruleType) {
            this.ruleType = ruleType;
            return this;
        }

        public DqDialect getDialect() {
            return dialect;
        }

        public RuleContext setDialect(DqDialect dialect) {
            this.dialect = dialect == null ? DqDialect.MYSQL : dialect;
            return this;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public RuleContext setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        public String getTableName() {
            return tableName;
        }

        public RuleContext setTableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public String getColumnName() {
            return columnName;
        }

        public RuleContext setColumnName(String columnName) {
            this.columnName = columnName;
            return this;
        }

        public String getPartitionExpr() {
            return partitionExpr;
        }

        public RuleContext setPartitionExpr(String partitionExpr) {
            this.partitionExpr = partitionExpr;
            return this;
        }

        public String getRefDatabaseName() {
            return refDatabaseName;
        }

        public RuleContext setRefDatabaseName(String refDatabaseName) {
            this.refDatabaseName = refDatabaseName;
            return this;
        }

        public String getRefTableName() {
            return refTableName;
        }

        public RuleContext setRefTableName(String refTableName) {
            this.refTableName = refTableName;
            return this;
        }

        public String getRefColumnName() {
            return refColumnName;
        }

        public RuleContext setRefColumnName(String refColumnName) {
            this.refColumnName = refColumnName;
            return this;
        }

        public String getExpectedValue() {
            return expectedValue;
        }

        public RuleContext setExpectedValue(String expectedValue) {
            this.expectedValue = expectedValue;
            return this;
        }

        public String getRefExpectedValue() {
            return refExpectedValue;
        }

        public RuleContext setRefExpectedValue(String refExpectedValue) {
            this.refExpectedValue = refExpectedValue;
            return this;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public RuleContext setParams(Map<String, String> params) {
            this.params = params == null ? Map.of() : params;
            return this;
        }

        public LocalDate getBatchDate() {
            return batchDate;
        }

        public RuleContext setBatchDate(LocalDate batchDate) {
            this.batchDate = batchDate == null ? LocalDate.now() : batchDate;
            return this;
        }
    }

    private DqSqlBuilder() {
    }

    // ==================================================================
    // 对外入口
    // ==================================================================

    /**
     * 编译内置模板（7 类规则类型）。
     *
     * @throws BusinessException 规则类型为空、必填项缺失、或占位符无法解析
     */
    public static CompiledSql compile(RuleContext ctx) {
        if (ctx == null || ctx.getRuleType() == null) {
            throw new BusinessException("规则类型不能为空");
        }
        DqBuiltinTemplates.Definition template = DqBuiltinTemplates.of(ctx.getRuleType());
        if (template == null) {
            throw new BusinessException("未找到规则类型对应的内置模板: " + ctx.getRuleType().getCode());
        }
        requireScopeFields(ctx);
        Map<String, String> placeholders = buildPlaceholders(ctx, template.metricKind());
        return render(template.sqlTemplate(), placeholders, ctx, template.metricKind(), template.ruleType());
    }

    /**
     * 校验「规则类型要求的配置项」是否齐备。
     *
     * <p>字段名在占位符层面是可选参数（表级规则本来就没有字段），
     * 所以 {@link #identifier} 无法代劳这件事：一个字段级规则缺字段时，
     * 模板里的 {@code ${column}} 会替换成空串，渲染出一条
     * {@code WHERE (  IS NULL)} 这样的残句 —— 它语法不成立，
     * 却会一路通过所有渲染期检查，直到下发到目标库才报错。</p>
     *
     * <p>保存入口（{@code DqRuleService.applyDto}）已有同样的校验，这里是<b>下发前的最后一道闸</b>：
     * 规则可能是在该校验存在之前创建的，也可能被直接改库，渲染器不能假设上游一定校验过。</p>
     */
    private static void requireScopeFields(RuleContext ctx) {
        QualityRuleType type = ctx.getRuleType();
        if (type.isColumnScope() && isBlank(ctx.getColumnName())) {
            throw new BusinessException("「" + type.getLabel()
                    + "」是字段级规则，缺少字段名，无法渲染校验 SQL");
        }
        if (type.isPairScope() && isBlank(ctx.getRefTableName())) {
            throw new BusinessException("「" + type.getLabel()
                    + "」是跨表规则，缺少对照表名，无法渲染校验 SQL");
        }
    }

    /**
     * 编译自定义模板：模板正文由用户维护，占位符集合比内置模板更宽
     * （额外允许 {@code ${expectedValue}} / {@code ${warnThreshold}} / {@code ${errorThreshold}}
     * 与 {@code ${params.xxx}}）。
     *
     * <p>自定义模板仍需归属 7 类之一（{@code ruleType} 不为空），因为「实际值怎么算」
     * 必须由 {@link MetricKind} 决定，而这个语义只能来自规则类型。</p>
     */
    public static CompiledSql compileCustom(String sqlTemplate, RuleContext ctx) {
        if (ctx == null || ctx.getRuleType() == null) {
            throw new BusinessException("自定义模板也必须归属 7 类规则类型之一");
        }
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new BusinessException("自定义模板的 SQL 模板不能为空");
        }
        if (ctx.getRuleType() == QualityRuleType.UPSTREAM_DOWNSTREAM_RECON
                || ctx.getRuleType() == QualityRuleType.METRIC_CONSISTENCY) {
            // 跨表比对要求 SQL 返回「主表值、对照表值」两列，自定义模板无法保证列序与列数
            throw new BusinessException("跨表比对类规则（上下游对账 / 口径一致性）不支持自定义模板");
        }
        // 自定义模板同样受「字段级规则必须有字段」约束：没有字段的字段级规则
        // 没有任何可校验的对象，渲染出来也是一条查不出问题的 SQL
        if (ctx.getRuleType().isColumnScope() && isBlank(ctx.getColumnName())) {
            throw new BusinessException("「" + ctx.getRuleType().getLabel()
                    + "」是字段级规则，缺少字段名，无法渲染校验 SQL");
        }
        Map<String, String> placeholders = buildPlaceholders(ctx, MetricKind.ABNORMAL_ROW_COUNT);
        // 自定义模板允许直接引用阈值，便于写「> ${errorThreshold} 即异常」这类 SQL
        placeholders.put("expectedValue", nullSafe(ctx.getExpectedValue()));
        placeholders.put("refExpectedValue", nullSafe(ctx.getRefExpectedValue()));
        return render(sqlTemplate, placeholders, ctx, MetricKind.ABNORMAL_ROW_COUNT, ctx.getRuleType());
    }

    // ==================================================================
    // 占位符
    // ==================================================================

    private static Map<String, String> buildPlaceholders(RuleContext ctx, MetricKind metricKind) {
        DqDialect dialect = ctx.getDialect();
        Map<String, String> map = new LinkedHashMap<>();

        map.put("table", qualifiedTable(dialect, ctx.getDatabaseName(), ctx.getTableName(), "表名"));
        map.put("column", identifier(dialect, ctx.getColumnName(), "字段名", false));
        map.put("charType", dialect.getCharType());

        String partition = partitionCondition(ctx.getPartitionExpr(), ctx.getBatchDate());
        map.put("partitionWhere", partition.isEmpty() ? "" : " WHERE (" + partition + ")");
        map.put("partition", partition.isEmpty() ? "" : " AND (" + partition + ")");

        if (metricKind == MetricKind.DIFF_PERCENT) {
            map.put("refTable", qualifiedTable(dialect, ctx.getRefDatabaseName(), ctx.getRefTableName(), "对照表名"));
            map.put("refColumn", identifier(dialect, ctx.getRefColumnName(), "对照字段名", false));
            map.put("refPartitionWhere", map.get("partitionWhere"));
            map.putAll(aggregatePlaceholders(ctx, dialect));
        }
        if (ctx.getRuleType() == QualityRuleType.VALUE_RANGE) {
            map.put("predicate", valueRangePredicate(ctx, dialect));
        }
        return map;
    }

    /**
     * 组装聚合表达式：记录数对账用 {@code COUNT(*)}，金额对账用 {@code SUM(字段)}。
     *
     * <p>聚合口径取自 {@code params.aggMode}，默认为规则类型定义里的默认值 ——
     * 刻意不让用户提交裸 SQL 片段，避免「聚合表达式」变成注入入口。</p>
     */
    private static Map<String, String> aggregatePlaceholders(RuleContext ctx, DqDialect dialect) {
        String aggMode = param(ctx, DqBuiltinTemplates.PARAM_AGG_MODE,
                ctx.getRuleType() == QualityRuleType.METRIC_CONSISTENCY ? "sum" : "count");
        boolean sum = "sum".equalsIgnoreCase(aggMode);
        Map<String, String> map = new LinkedHashMap<>();
        if (!sum) {
            map.put("mainAgg", "COUNT(*)");
            map.put("refAgg", "COUNT(*)");
            return map;
        }
        if (isBlank(ctx.getColumnName())) {
            throw new BusinessException("聚合口径为 sum 时必须指定求和字段");
        }
        map.put("mainAgg", "SUM(" + dialect.quoteIdentifier(ctx.getColumnName().trim()) + ")");
        String refColumn = isBlank(ctx.getRefColumnName()) ? ctx.getColumnName() : ctx.getRefColumnName();
        map.put("refAgg", "SUM(" + dialect.quoteIdentifier(refColumn.trim()) + ")");
        return map;
    }

    /**
     * 由 {@code expectedValue} 推出值域判定谓词。
     *
     * <p>两种写法：{@code [下界,上界]} 视为闭区间；其余视为枚举字典（英文逗号分隔）。</p>
     */
    private static String valueRangePredicate(RuleContext ctx, DqDialect dialect) {
        String expected = ctx.getExpectedValue();
        if (isBlank(expected)) {
            throw new BusinessException("值域规则必须在期望值里写明允许取值：区间写 [下界,上界]，枚举写英文逗号分隔的取值");
        }
        String trimmed = expected.trim();
        Matcher interval = INTERVAL_PATTERN.matcher(trimmed);
        if (interval.matches()) {
            BigDecimal min = new BigDecimal(interval.group(1));
            BigDecimal max = new BigDecimal(interval.group(2));
            if (min.compareTo(max) > 0) {
                throw new BusinessException("值域区间下界不能大于上界: " + trimmed);
            }
            String column = dialect.quoteIdentifier(ctx.getColumnName().trim());
            return column + " < " + min.toPlainString() + " OR " + column + " > " + max.toPlainString();
        }

        List<String> values = new ArrayList<>();
        for (String raw : trimmed.split(",")) {
            String value = raw.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw new BusinessException("值域规则的枚举字典为空: " + trimmed);
        }
        StringBuilder literals = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                literals.append(", ");
            }
            literals.append(dialect.literal(values.get(i)));
        }
        return "TRIM(CAST(" + dialect.quoteIdentifier(ctx.getColumnName().trim())
                + " AS " + dialect.getCharType() + ")) NOT IN (" + literals + ")";
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.]*)}");

    /**
     * 把模板里的 {@code ${...}} 全部替换掉。
     *
     * <p>渲染后仍有残留占位符即抛错：宁可让规则配不出来，也不能把「少了字段的 SQL」
     * 下发到生产库 —— 那种 SQL 往往语法合法但语义完全不同（例如漏了分区条件变成全表校验）。</p>
     */
    private static CompiledSql render(String template, Map<String, String> placeholders, RuleContext ctx,
                                      MetricKind metricKind, QualityRuleType ruleType) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder buffer = new StringBuilder(template.length() + 128);
        Map<String, String> used = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = resolve(key, placeholders, ctx);
            if (value == null) {
                missing.add(key);
                value = "";
            } else {
                used.put(key, value);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);

        if (!missing.isEmpty()) {
            throw new BusinessException("校验 SQL 模板缺少必要占位符取值: " + String.join(", ", missing)
                    + "。请补齐规则配置（表名/字段/对照表/期望值）后重试。");
        }

        String sql = stripComments(buffer.toString()).trim();
        if (sql.isEmpty()) {
            throw new BusinessException("渲染后的校验 SQL 为空，请检查模板配置");
        }
        if (!isSingleSelectStatement(sql)) {
            throw new BusinessException("校验 SQL 必须是单条 SELECT/WITH 查询（不得含语句分隔符）");
        }
        return new CompiledSql(sql, checkObject(ctx, ruleType), metricKind, columnCount(metricKind), used);
    }

    /** 解析单个占位符：先查显式表，再支持 {@code params.xxx} 形式 */
    private static String resolve(String key, Map<String, String> placeholders, RuleContext ctx) {
        if (key.startsWith("params.")) {
            String name = key.substring("params.".length());
            String value = ctx.getParams().get(name);
            return value == null ? "" : value;
        }
        if (!placeholders.containsKey(key)) {
            return null;
        }
        String value = placeholders.get(key);
        if (isBlank(value)) {
            // 空串是合法取值（例如无分区条件时的 ${partition}），因此不当成缺失
            return value == null ? "" : value;
        }
        return value;
    }

    private static int columnCount(MetricKind metricKind) {
        return metricKind == MetricKind.DIFF_PERCENT ? 2 : 1;
    }

    /**
     * 校验对象展示串 —— 对应契约里的 {@code checkObject}，用于「结果可定位」。
     *
     * <p>字段级：{@code 库.表.字段}；表级：{@code 库.表}；
     * 跨表：{@code 库.表.口径 ↔ 对照库.对照表.口径}。</p>
     */
    public static String checkObject(RuleContext ctx, QualityRuleType ruleType) {
        String left = qualify(ctx.getDatabaseName(), ctx.getTableName());
        if (ruleType == null) {
            return left;
        }
        return switch (ruleType.getScope()) {
            case COLUMN -> left + "." + nullSafe(ctx.getColumnName());
            case TABLE -> left;
            case PAIR -> left + "." + aggregateLabel(ctx.getColumnName())
                    + " ↔ " + qualify(ctx.getRefDatabaseName(), ctx.getRefTableName())
                    + "." + aggregateLabel(isBlank(ctx.getRefColumnName()) ? ctx.getColumnName() : ctx.getRefColumnName());
        };
    }

    private static String aggregateLabel(String column) {
        return isBlank(column) ? "记录数" : column;
    }

    private static String qualify(String databaseName, String tableName) {
        return isBlank(databaseName) ? nullSafe(tableName) : databaseName + "." + tableName;
    }

    // ==================================================================
    // 分区表达式
    // ==================================================================

    /** 形如 {@code ${yyyyMMdd}} / {@code ${yyyyMMdd-1d}} / {@code ${yyyy-MM-dd}} 的日期占位符 */
    private static final Pattern DATE_PLACEHOLDER =
            Pattern.compile("\\$\\{(yyyyMMdd|yyyy-MM-dd|yyyyMMddHH)(?:([-+])(\\d+)([dh]))?}");

    /** 值域区间写法：[下界,上界] */
    private static final Pattern INTERVAL_PATTERN =
            Pattern.compile("^\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*]$");

    /** 分区条件里禁止出现的语句级关键字（防止分区表达式被当成注入入口） */
    private static final Pattern FORBIDDEN_IN_PARTITION = Pattern.compile(
            "(?i)\\b(select|insert|update|delete|merge|union|drop|alter|create|truncate|grant|revoke|call|exec|execute|from|join)\\b");

    /**
     * 把分区表达式展开为可直接放进 WHERE 的布尔条件。
     *
     * <p>日期占位符按批次日期展开：{@code ${yyyyMMdd}}、{@code ${yyyy-MM-dd}}、
     * {@code ${yyyyMMddHH}}，并支持 {@code -1d} / {@code +2h} 之类的偏移。
     * 不含占位符时按字面条件使用（例如 {@code dt = '20260917'}）。</p>
     *
     * <p>返回空串表示「不做分区过滤」。含语句分隔符、注释符或子查询关键字的表达式一律拒绝：
     * 分区条件会被拼进校验 SQL 直接下发，必须挡住注入面。</p>
     */
    public static String partitionCondition(String partitionExpr, LocalDate batchDate) {
        if (isBlank(partitionExpr)) {
            return "";
        }
        String raw = partitionExpr.trim();
        if (raw.contains(";") || raw.contains("--") || raw.contains("/*") || raw.contains("#")) {
            throw new BusinessException("分区表达式不能包含语句分隔符或注释符: " + partitionExpr);
        }
        LocalDate base = batchDate == null ? LocalDate.now() : batchDate;

        Matcher matcher = DATE_PLACEHOLDER.matcher(raw);
        StringBuilder expanded = new StringBuilder(raw.length() + 32);
        while (matcher.find()) {
            String pattern = matcher.group(1);
            String sign = matcher.group(2);
            int amount = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            String unit = matcher.group(4);
            LocalDateTime moment = base.atStartOfDay();
            if (amount > 0) {
                moment = "-".equals(sign) ? moment.minusDays(amount) : moment.plusDays(amount);
                if ("h".equalsIgnoreCase(unit)) {
                    moment = "-".equals(sign) ? moment.plusDays(amount).minusHours(amount)
                            : moment.minusDays(amount).plusHours(amount);
                }
            }
            String rendered = switch (pattern) {
                case "yyyy-MM-dd" -> moment.format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "yyyyMMddHH" -> moment.format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
                default -> moment.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            };
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(expanded);

        String condition = expanded.toString().trim();
        if (condition.isEmpty()) {
            return "";
        }
        if (FORBIDDEN_IN_PARTITION.matcher(condition).find()) {
            throw new BusinessException("分区表达式不能包含子查询或语句级关键字: " + partitionExpr);
        }
        // 日期值按约定不加引号书写（dt=${yyyyMMdd}），这里补上引号让各方言都能解析
        condition = quoteBareDateComparisons(condition);
        return condition;
    }

    /**
     * 把 {@code dt=20260917} 这种「裸日期比较」补成 {@code dt='20260917'}。
     *
     * <p>提示词与界面约定分区表达式写 {@code dt=${yyyyMMdd}}（不带引号），
     * 展开后就成了 {@code dt=20260917} —— 在 MySQL 上能跑，在 PG 上会因为
     * 「整数与 varchar 比较」直接报错。这里统一补引号，让同一份配置跨方言可用。</p>
     */
    private static String quoteBareDateComparisons(String condition) {
        Matcher matcher = BARE_LITERAL_COMPARISON.matcher(condition);
        StringBuilder buffer = new StringBuilder(condition.length() + 16);
        while (matcher.find()) {
            String operator = matcher.group(1);
            String value = matcher.group(2);
            // 已经是带引号的字面量时不重复处理
            String replacement = value.startsWith("'")
                    ? matcher.group(0)
                    : operator + "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 比较运算右侧的裸字面量：数字、日期、`20260917`、`2026-09-17`、`2026091710` */
    private static final Pattern BARE_LITERAL_COMPARISON = Pattern.compile(
            "(=|<>|!=|<=|>=|<|>)\\s*('(?:[^']|'')*'|\\d{4}-\\d{2}-\\d{2}|\\d{4,14})");

    // ==================================================================
    // 标识符与语句校验
    // ==================================================================

    /**
     * 标识符字符集白名单。
     *
     * <p>引号、反引号、分号、反斜杠、空白、括号、方括号、花括号一律禁止：
     * 这些字符一旦进入标识符位，就是拼接类注入的入口。允许 {@code $} 与中划线，
     * 因为物理库表名确实存在这种写法（会被引用符包住，不影响解析）。</p>
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[^\\s\"'`;\\\\()\\[\\]{}.,*=/]{1,128}$");

    /**
     * 校验并引用一个标识符。
     *
     * @param required 为 true 时为空直接抛错；为 false 时为空返回空串
     */
    public static String identifier(DqDialect dialect, String raw, String fieldLabel, boolean required) {
        if (isBlank(raw)) {
            if (required) {
                throw new BusinessException(fieldLabel + "不能为空");
            }
            return "";
        }
        String trimmed = raw.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new BusinessException(fieldLabel + "含非法字符（只允许字母、数字、下划线、$ 与中划线）: " + raw);
        }
        return dialect.quoteIdentifier(trimmed);
    }

    /** 渲染「库.表」限定名；表名必填 */
    public static String qualifiedTable(DqDialect dialect, String databaseName, String tableName, String fieldLabel) {
        String table = identifier(dialect, tableName, fieldLabel, true);
        if (isBlank(databaseName)) {
            return table;
        }
        return identifier(dialect, databaseName, "库名", true) + "." + table;
    }

    /**
     * 是否单条查询语句。
     *
     * <p>校验 SQL 会被真实下发，必须确保它只有一条语句：含分号即视为多条，
     * 避免「配置一条选区、实际执行到删表」这类越权。</p>
     */
    public static boolean isSingleSelectStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String normalized = stripComments(sql).trim();
        if (normalized.contains(";")) {
            return false;
        }
        String upper = normalized.toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("WITH");
    }

    /** 去掉 {@code --} 行注释与 {@code /* *}{@code /} 块注释，并压缩多余空白 */
    public static String stripComments(String sql) {
        if (sql == null) {
            return "";
        }
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(withoutBlock.length());
        for (String line : withoutBlock.split("\n", -1)) {
            int comment = line.indexOf("--");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> kept = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                kept.add(trimmed);
            }
        }
        return String.join(" ", kept);
    }

    // ==================================================================
    // 参数
    // ==================================================================

    private static final ObjectMapper PARAM_MAPPER = new ObjectMapper();

    /**
     * 解析规则的可选占位符参数（存于 dq_rule.params 的 JSON 对象）。
     *
     * <p>解析失败不抛错、退化为空表：参数是可选增强，写坏了应退回默认口径继续跑校验，
     * 不能因为一个可选字段的格式问题让整条规则无法执行。</p>
     */
    public static Map<String, String> parseParams(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = PARAM_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String param(RuleContext ctx, String key, String defaultValue) {
        String value = ctx.getParams().get(key);
        return isBlank(value) ? defaultValue : value.trim();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
