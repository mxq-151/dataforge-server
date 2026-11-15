package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRuleTemplate;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.bigdata.modules.quality.enums.QualityTemplateKind;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqRuleTemplateMapper;
import com.bigdata.modules.quality.sql.DqRuleEvaluator;
import com.bigdata.modules.quality.sql.DqSqlBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 质检规则编译与执行编排的异常路径。
 *
 * <p>这里是质量模块唯一的下发出口，编译失败 / 数据源不可用 / 执行失败都不能被当成
 * 「校验通过」。此前该类行覆盖 1.6%，所有错误分支都没被执行过。</p>
 */
@DisplayName("Quality模块 - 规则编译与执行编排")
class DqExecutionServiceTest {

    private static final LocalDate BATCH_DATE = LocalDate.of(2026, 11, 15);

    private DqExecutionService service;
    private CodeExecutionService codeExecutionService;
    private DatasourceConfigService datasourceConfigService;
    private DqRuleTemplateMapper templateMapper;
    private DqRuleResultMapper resultMapper;

    @BeforeEach
    void setUp() {
        service = new DqExecutionService();
        codeExecutionService = mock(CodeExecutionService.class);
        datasourceConfigService = mock(DatasourceConfigService.class);
        templateMapper = mock(DqRuleTemplateMapper.class);
        resultMapper = mock(DqRuleResultMapper.class);
        ReflectionTestUtils.setField(service, "codeExecutionService", codeExecutionService);
        ReflectionTestUtils.setField(service, "datasourceConfigService", datasourceConfigService);
        ReflectionTestUtils.setField(service, "dqRuleTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "dqRuleResultMapper", resultMapper);
    }

    @Test
    @DisplayName("规则类型无法识别：编译期直接拒绝，禁止自造同义词")
    void rejectsUnknownRuleType() {
        DqRule rule = baseRule();
        rule.setRuleType("COUNT_CHECK");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.compile(rule, BATCH_DATE));
        assertTrue(ex.getMessage().contains("无法识别的规则类型"));
    }

    @Test
    @DisplayName("规则没有关联数据源：编译期拒绝")
    void rejectsMissingDatasource() {
        DqRule rule = baseRule();
        rule.setDatasourceId(8L);
        rule.setDatasourceName(null);
        when(datasourceConfigService.getDatasourceById(8L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.compile(rule, BATCH_DATE));
        assertTrue(ex.getMessage().contains("未关联到有效数据源"));
    }

    @Test
    @DisplayName("只填了数据源名称：回落到按名称解析")
    void resolvesDatasourceByName() {
        DqRule rule = baseRule();
        rule.setDatasourceId(99L);
        rule.setDatasourceName("pg-main");
        when(datasourceConfigService.getDatasourceById(99L)).thenReturn(null);
        when(datasourceConfigService.getDatasourceByName("pg-main")).thenReturn(postgres());

        assertFalse(service.compile(rule, BATCH_DATE).sql().isBlank());
    }

    @Test
    @DisplayName("数据源未配置引擎类型：拒绝并提示")
    void rejectsDatasourceWithoutType() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("broken");
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(config);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.compile(rule, BATCH_DATE));
        assertTrue(ex.getMessage().contains("未配置引擎类型"));
    }

    @Test
    @DisplayName("数据源不支持 SQL（ES）：拒绝而不是拼一个跑不了的 SQL")
    void rejectsDatasourceWithoutSqlSupport() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("es-cluster");
        config.setDatasourceType(DatasourceType.ES);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(config);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.compile(rule, BATCH_DATE));
        assertTrue(ex.getMessage().contains("不支持 SQL 执行"));
    }

    @Test
    @DisplayName("自定义模板：按模板 SQL 渲染，而不是走内置模板")
    void usesCustomTemplateSql() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        rule.setTemplateId(5L);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());
        DqRuleTemplate template = new DqRuleTemplate();
        template.setTemplateCode("CUSTOM_X");
        template.setTemplateKind(QualityTemplateKind.CUSTOM.getCode());
        template.setSqlTemplate("SELECT 123 AS v");
        when(templateMapper.selectById(5L)).thenReturn(template);

        assertTrue(service.compile(rule, BATCH_DATE).sql().contains("123"));
    }

    @Test
    @DisplayName("内置模板（无 templateId）：走统一编译器")
    void compilesBuiltinTemplate() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());

        assertTrue(service.compile(rule, BATCH_DATE).sql().contains("demo_t"));
    }

    @Test
    @DisplayName("预览编译：规则类型非法时同样拒绝")
    void previewRejectsUnknownRuleType() {
        DqRule pseudo = baseRule();
        pseudo.setRuleType("NOT_A_TYPE");
        pseudo.setDatasourceId(1L);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());

        assertThrows(BusinessException.class,
                () -> service.compileForPreview(null, pseudo, BATCH_DATE));
    }

    @Test
    @DisplayName("执行成功：按结果首行判定，耗时取执行结果")
    void executesAndEvaluates() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        rule.setExpectedValue("0");
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());
        CodeExecutionResult execution = CodeExecutionResult.success("e1", "ok",
                List.of("cnt"), List.of(List.of("0")), 1);
        execution.setDurationMs(37);
        when(codeExecutionService.executeCode(any(), any(), any())).thenReturn(execution);

        DqExecutionService.Outcome outcome = service.execute(rule, BATCH_DATE, null);

        assertEquals(QualityCheckStatus.PASS, outcome.evaluation().status());
        assertEquals(37L, outcome.costMs());
        assertNull(outcome.errorMessage());
    }

    @Test
    @DisplayName("执行失败：判定为 FAILED，错误信息透传自执行结果")
    void failedExecutionIsNotTreatedAsPass() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());
        when(codeExecutionService.executeCode(any(), any(), any()))
                .thenReturn(CodeExecutionResult.failure("e2", "table not found"));

        DqExecutionService.Outcome outcome = service.execute(rule, BATCH_DATE, null);

        assertEquals(QualityCheckStatus.FAILED, outcome.evaluation().status());
        assertEquals("table not found", outcome.errorMessage());
        assertEquals(0L, outcome.costMs());
    }

    @Test
    @DisplayName("首行取值：空结果/空行都返回空列表")
    void firstRowHandlesEmpty() {
        assertTrue(DqExecutionService.firstRow(null).isEmpty());
        assertTrue(DqExecutionService.firstRow(CodeExecutionResult.success("e", "ok")).isEmpty());
        CodeExecutionResult withNullRow = CodeExecutionResult.success("e", "ok",
                List.of("c"), java.util.Arrays.asList((List<String>) null), 0);
        assertTrue(DqExecutionService.firstRow(withNullRow).isEmpty());
    }

    @Test
    @DisplayName("原始度量值：跨表比对保留两列，缺列返回 null")
    void metricValueKeepsBothSidesForDiff() {
        assertEquals("105,100",
                DqExecutionService.metricValue(DqSqlBuilder.MetricKind.DIFF_PERCENT, List.of("105", "100")));
        assertNull(DqExecutionService.metricValue(DqSqlBuilder.MetricKind.DIFF_PERCENT, List.of("105")));
        assertEquals("7",
                DqExecutionService.metricValue(DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT, List.of("7")));
        assertNull(DqExecutionService.metricValue(DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT, List.of()));
    }

    @Test
    @DisplayName("非行数类规则不需要基线")
    void baselineOnlyForRowCountRules() {
        DqRule rule = baseRule();
        rule.setId(1L);

        assertNull(service.resolveBaseline(rule));
    }

    @Test
    @DisplayName("规则未落库（无 id）时没有基线")
    void baselineRequiresRuleId() {
        DqRule rule = baseRule();
        rule.setRuleType(QualityRuleType.ROW_COUNT_MOM.getCode());
        rule.setId(null);

        assertNull(service.resolveBaseline(rule));
    }

    @Test
    @DisplayName("行数环比：取最近一次执行的当期行数")
    void baselineUsesLatestMetricForMom() {
        DqRule rule = baseRule();
        rule.setId(3L);
        rule.setRuleType(QualityRuleType.ROW_COUNT_MOM.getCode());
        stubBaselineRows(rule, "100");

        assertEquals(new BigDecimal("100.0000"), service.resolveBaseline(rule));
    }

    @Test
    @DisplayName("行数波动：取有效数值的均值，非法值被跳过")
    void baselineAveragesValidValues() {
        DqRule rule = baseRule();
        rule.setId(4L);
        rule.setRuleType(QualityRuleType.ROW_COUNT_FLUCTUATION.getCode());
        rule.setParams("{\"baselineDays\":\"3\"}");
        stubBaselineRows(rule, "10", "abc", "20");

        assertEquals(new BigDecimal("15.0000"), service.resolveBaseline(rule));
    }

    @Test
    @DisplayName("没有历史记录：返回 null，交由判定器判「无基线」")
    void baselineIsNullWithoutHistory() {
        DqRule rule = baseRule();
        rule.setId(5L);
        rule.setRuleType(QualityRuleType.ROW_COUNT_MOM.getCode());
        stubBaselineRows(rule);

        assertNull(service.resolveBaseline(rule));
    }

    @Test
    @DisplayName("默认比较方式为小于等于（未配置 compareMode）")
    void defaultsToLteCompareMode() {
        DqRule rule = baseRule();
        rule.setDatasourceId(1L);
        rule.setExpectedValue("0");
        rule.setCompareMode(null);
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(postgres());
        when(codeExecutionService.executeCode(any(), any(), any()))
                .thenReturn(CodeExecutionResult.success("e3", "ok",
                        List.of("cnt"), List.of(List.of("1")), 1));

        DqExecutionService.Outcome outcome = service.execute(rule, BATCH_DATE, null);

        assertEquals(QualityCheckStatus.ERROR, outcome.evaluation().status());
        assertTrue(outcome.evaluation().threshold().startsWith("≤"));
    }

    private void stubBaselineRows(DqRule rule, String... metricValues) {
        List<DqRuleResult> records = new java.util.ArrayList<>();
        for (String value : metricValues) {
            DqRuleResult row = new DqRuleResult();
            row.setMetricValue(value);
            records.add(row);
        }
        when(resultMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<DqRuleResult> page = invocation.getArgument(0);
            page.setRecords(records);
            return page;
        });
    }

    private DqRule baseRule() {
        DqRule rule = new DqRule();
        rule.setRuleName("非空校验");
        rule.setRuleType(QualityRuleType.NOT_NULL.getCode());
        rule.setDatabaseName("demo_db");
        rule.setTableName("demo_t");
        rule.setColumnName("id");
        return rule;
    }

    private DatasourceConfig postgres() {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("pg-main");
        config.setDatasourceType(DatasourceType.POSTGRESQL);
        config.setDatabaseName(List.of("demo_db"));
        return config;
    }
}
