package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.CodeType;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRuleTemplate;
import com.bigdata.modules.quality.enums.DqDialect;
import com.bigdata.modules.quality.enums.QualityCompareMode;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.bigdata.modules.quality.enums.QualityTemplateKind;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqRuleTemplateMapper;
import com.bigdata.modules.quality.sql.DqBuiltinTemplates;
import com.bigdata.modules.quality.sql.DqRuleEvaluator;
import com.bigdata.modules.quality.sql.DqSqlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验规则执行器 —— 把一条规则编译成 SQL 并**真实下发**到目标引擎执行。
 *
 * <p>这是质量模块唯一的下发出口：试跑、任务执行、结果上报全部走这里，
 * 保证「怎么编译」「怎么判定」只有一份实现。</p>
 *
 * <h3>为什么必须经 {@link CodeExecutionService} 下发，而不是自己开 JDBC 连接</h3>
 * <p>自己连库绕不过平台的安全层。走 CodeExecutionService 才能拿到两件事：
 * <ul>
 *   <li><b>语句级表权限校验</b>：校验 SQL 里引用的每一张表都会被检查 SELECT 权限；</li>
 *   <li><b>行级过滤 / 列白名单改写</b>：{@code SqlPermissionRewriter} 在下发前把行过滤
 *       注入 WHERE、把投影裁剪到列白名单 —— 于是「用质检规则读取他人无权查看的行」
 *       这条路在引擎侧就被堵住了，而不是靠事后过滤。</li>
 * </ul>
 * 代价是需要 SecurityContext，因此所有调用都必须发生在已认证的请求线程
 * （异步执行时由调用方负责把 context 传进工作线程）。</p>
 *
 * <h3>历史基线</h3>
 * <p>行数波动 / 行数环比的实际值是涨跌幅，需要「历史行数」当分母。
 * 基线不是配置出来的，而是从 dq_rule_result 的历史留痕里算出来的 ——
 * 因此这两类规则在首次执行时必然判「校验失败（无历史基线）」，
 * 这是刻意的：首期没有任何参照物，判通过就是假绿。</p>
 */
@Service
public class DqExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DqExecutionService.class);

    @Autowired
    private CodeExecutionService codeExecutionService;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private DqRuleTemplateMapper dqRuleTemplateMapper;

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    /**
     * 一次执行的完整留痕。
     *
     * @param compiled  编译结果（含实际下发的 SQL 与校验对象）
     * @param execution 底层执行结果（用于取 columns / durationMs / errorMessage）
     * @param evaluation 判定结果
     */
    public record Outcome(
            DqSqlBuilder.CompiledSql compiled,
            CodeExecutionResult execution,
            DqRuleEvaluator.Evaluation evaluation) {

        /** 本次执行耗时（毫秒），落 dq_rule_result.cost_ms */
        public long costMs() {
            Integer duration = execution == null ? null : execution.getDurationMs();
            return duration == null ? 0L : duration;
        }

        /** 校验失败时的原因，用于回填 error_msg */
        public String errorMessage() {
            if (execution != null && !execution.isSuccess() && execution.getErrorMessage() != null) {
                return execution.getErrorMessage();
            }
            return evaluation == null ? null : evaluation.errorMsg();
        }
    }

    // ==================================================================
    // 编译
    // ==================================================================

    /**
     * 编译一条规则（内置模板或自定义模板）。
     *
     * @throws BusinessException 规则类型非法、数据源不可用、模板占位符缺失
     */
    public DqSqlBuilder.CompiledSql compile(DqRule rule, LocalDate batchDate) {
        QualityRuleType ruleType = QualityRuleType.fromCode(rule.getRuleType());
        if (ruleType == null) {
            throw new BusinessException("无法识别的规则类型: " + rule.getRuleType()
                    + "（必须是 7 类之一，禁止自造同义词）");
        }
        DatasourceConfig config = resolveDatasource(rule);
        DqDialect dialect = DqDialect.of(config.getDatasourceType());
        DqSqlBuilder.RuleContext ctx = buildContext(rule, ruleType, dialect, batchDate);

        DqRuleTemplate template = rule.getTemplateId() == null ? null
                : dqRuleTemplateMapper.selectById(rule.getTemplateId());
        if (template != null && QualityTemplateKind.CUSTOM.getCode().equalsIgnoreCase(template.getTemplateKind())) {
            log.debug("规则 {} 使用自定义模板 {} 渲染校验 SQL", rule.getId(), template.getTemplateCode());
            return DqSqlBuilder.compileCustom(template.getSqlTemplate(), ctx);
        }
        return DqSqlBuilder.compile(ctx);
    }

    /** 按数据源引擎方言编译「模板预览」SQL，不落库、不执行 */
    public DqSqlBuilder.CompiledSql compileForPreview(DqRuleTemplate template, DqRule pseudoRule, LocalDate batchDate) {
        DatasourceConfig config = resolveDatasource(pseudoRule);
        DqDialect dialect = DqDialect.of(config.getDatasourceType());
        QualityRuleType ruleType = QualityRuleType.fromCode(pseudoRule.getRuleType());
        if (ruleType == null) {
            throw new BusinessException("无法识别的规则类型: " + pseudoRule.getRuleType());
        }
        DqSqlBuilder.RuleContext ctx = buildContext(pseudoRule, ruleType, dialect, batchDate);
        if (template != null && QualityTemplateKind.CUSTOM.getCode().equalsIgnoreCase(template.getTemplateKind())) {
            return DqSqlBuilder.compileCustom(template.getSqlTemplate(), ctx);
        }
        return DqSqlBuilder.compile(ctx);
    }

    private DqSqlBuilder.RuleContext buildContext(DqRule rule, QualityRuleType ruleType,
                                                  DqDialect dialect, LocalDate batchDate) {
        Map<String, String> params = new LinkedHashMap<>(DqRuleEvaluator.defaultsFor(ruleType.getCode()));
        params.putAll(DqSqlBuilder.parseParams(rule.getParams()));
        return new DqSqlBuilder.RuleContext()
                .setRuleType(ruleType)
                .setDialect(dialect)
                .setDatabaseName(rule.getDatabaseName())
                .setTableName(rule.getTableName())
                .setColumnName(rule.getColumnName())
                .setPartitionExpr(rule.getPartitionExpr())
                .setRefDatabaseName(rule.getRefDatabaseName())
                .setRefTableName(rule.getRefTableName())
                .setRefColumnName(rule.getRefColumnName())
                .setExpectedValue(rule.getExpectedValue())
                .setParams(params)
                .setBatchDate(batchDate);
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /**
     * 编译 + 下发执行 + 判定。
     *
     * @param batchDate 批次日期（分区表达式里的 ${yyyyMMdd} 按它展开）
     * @param baseline  历史基线；仅行数类规则需要，其余传 null
     */
    public Outcome execute(DqRule rule, LocalDate batchDate, BigDecimal baseline) {
        DqSqlBuilder.CompiledSql compiled = compile(rule, batchDate);
        DatasourceConfig config = resolveDatasource(rule);

        CodeExecutionRequest request = new CodeExecutionRequest();
        request.setCodeContent(compiled.sql());
        request.setCodeType(CodeType.SQL.getCode());
        request.setEngineType(config.getDatasourceType().getCode());
        request.setDatasources(List.of(config.getDatasourceName()));

        log.info("下发质检规则: rule={}({}), datasource={}, dialect={}, sql={}",
                rule.getRuleName(), rule.getRuleType(), config.getDatasourceName(),
                DqDialect.of(config.getDatasourceType()), compiled.sql());

        CodeExecutionResult execution = codeExecutionService.executeCode(
                request, SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUsername());

        List<String> values = firstRow(execution);
        DqRuleEvaluator.Evaluation evaluation;
        if (!execution.isSuccess()) {
            evaluation = DqRuleEvaluator.evaluate(compiled.metricKind(), compareMode(rule),
                    rule.getExpectedValue(), rule.getWarnThreshold(), rule.getErrorThreshold(),
                    List.of(), null);
        } else {
            evaluation = DqRuleEvaluator.evaluate(compiled.metricKind(), compareMode(rule),
                    rule.getExpectedValue(), rule.getWarnThreshold(), rule.getErrorThreshold(),
                    values, baseline);
        }
        return new Outcome(compiled, execution, evaluation);
    }

    /**
     * 解析历史基线。
     *
     * <ul>
     *   <li>行数波动：取该规则最近 {@code baselineDays} 次执行的当期行数均值；</li>
     *   <li>行数环比：取该规则最近一次执行的当期行数（即「上一期」）。</li>
     * </ul>
     *
     * @return 基线；无历史数据时返回 {@code null}（此时判定为「校验失败：无历史基线」）
     */
    public BigDecimal resolveBaseline(DqRule rule) {
        QualityRuleType ruleType = QualityRuleType.fromCode(rule.getRuleType());
        if (ruleType == null || rule.getId() == null) {
            return null;
        }
        int limit;
        if (ruleType == QualityRuleType.ROW_COUNT_FLUCTUATION) {
            Map<String, String> params = new LinkedHashMap<>(
                    DqRuleEvaluator.defaultsFor(ruleType.getCode()));
            params.putAll(DqSqlBuilder.parseParams(rule.getParams()));
            limit = parsePositiveInt(params.get(DqBuiltinTemplates.PARAM_BASELINE_DAYS),
                    DqBuiltinTemplates.DEFAULT_BASELINE_DAYS);
        } else if (ruleType == QualityRuleType.ROW_COUNT_MOM) {
            limit = 1;
        } else {
            return null;
        }

        LambdaQueryWrapper<DqRuleResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DqRuleResult::getRuleId, rule.getId())
                .isNotNull(DqRuleResult::getMetricValue)
                .ne(DqRuleResult::getMetricValue, "");
        wrapper.orderByDesc(DqRuleResult::getId);
        // 走分页插件生成方言无关的 LIMIT，避免用 last("LIMIT n") 在 PG/H2 上写出方言相关 SQL
        Page<DqRuleResult> page = dqRuleResultMapper.selectPage(new Page<>(1, limit), wrapper);

        List<BigDecimal> numbers = new ArrayList<>();
        for (DqRuleResult row : page.getRecords()) {
            BigDecimal value = toNumber(row.getMetricValue());
            if (value != null) {
                numbers.add(value);
            }
        }
        if (numbers.isEmpty()) {
            return null;
        }
        BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(numbers.size()), 4, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 解析规则所指向的数据源：优先按 id，回落到按名称（规则可能只填了名称） */
    public DatasourceConfig resolveDatasource(DqRule rule) {
        DatasourceConfig config = null;
        if (rule.getDatasourceId() != null) {
            config = datasourceConfigService.getDatasourceById(rule.getDatasourceId());
        }
        if (config == null && rule.getDatasourceName() != null && !rule.getDatasourceName().isBlank()) {
            config = datasourceConfigService.getDatasourceByName(rule.getDatasourceName());
        }
        if (config == null) {
            throw new BusinessException("规则未关联到有效数据源，请先在规则里选择数据源");
        }
        DatasourceType type = config.getDatasourceType();
        if (type == null) {
            throw new BusinessException("数据源 " + config.getDatasourceName() + " 未配置引擎类型");
        }
        if (!type.supportsLanguage(CodeType.SQL)) {
            throw new BusinessException("数据源 " + config.getDatasourceName() + "（" + type.getDescription()
                    + "）不支持 SQL 执行，无法用于数据质量校验");
        }
        return config;
    }

    /** 取结果集首行；无结果行时返回空列表 */
    static List<String> firstRow(CodeExecutionResult execution) {
        if (execution == null || execution.getRows() == null || execution.getRows().isEmpty()) {
            return List.of();
        }
        List<String> row = execution.getRows().get(0);
        return row == null ? List.of() : row;
    }

    /**
     * 组装落库用的「原始度量值」。
     *
     * <p>行数类存当期行数（下一期算基线的输入）；跨表比对存「主表值,对照值」
     * （只留相对差异无法判断是哪一侧变了）。</p>
     */
    public static String metricValue(DqSqlBuilder.MetricKind metricKind, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (metricKind == DqSqlBuilder.MetricKind.DIFF_PERCENT) {
            if (values.size() < 2) {
                return null;
            }
            return values.get(0) + "," + values.get(1);
        }
        return values.get(0);
    }

    private static QualityCompareMode compareMode(DqRule rule) {
        QualityCompareMode mode = QualityCompareMode.fromCode(rule.getCompareMode());
        return mode == null ? QualityCompareMode.LTE : mode;
    }

    private static BigDecimal toNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
