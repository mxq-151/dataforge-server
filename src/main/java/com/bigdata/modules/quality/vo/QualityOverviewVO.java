package com.bigdata.modules.quality.vo;

import com.bigdata.modules.quality.vo.QualityProblemTableVO;
import java.util.List;

/**
 * QualityOverviewVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * <p>已移除的两个字段：{@code passRateTrend}（按运行实例聚合的通过率趋势）与
 * {@code blockCount}（按任务处置策略统计的阻断次数）—— 运行实例与任务两条链路
 * 已随「运行记录 / 质检任务」页签删除，二者失去数据源。TOP 问题表保留：
 * 它改由 dq_rule_result（结果留痕）按时间窗直接聚合，不再依赖运行实例。</p>
 *
 * @author dataforge
 */
public class QualityOverviewVO {

    /** 已配置规则的表数 */
    private Integer coveredTableCount;

    /** 应配置规则的核心表数 */
    private Integer targetTableCount;

    /** 规则覆盖率 0~1 */
    private Double ruleCoverage;

    /** 规则总数 */
    private Integer ruleCount;

    /** 启用规则数 */
    private Integer enabledRuleCount;

    /** TOP 质量问题表 */
    private List<QualityProblemTableVO> topProblemTables;

    public Integer getCoveredTableCount() { return coveredTableCount; }
    public void setCoveredTableCount(Integer coveredTableCount) { this.coveredTableCount = coveredTableCount; }
    public Integer getTargetTableCount() { return targetTableCount; }
    public void setTargetTableCount(Integer targetTableCount) { this.targetTableCount = targetTableCount; }
    public Double getRuleCoverage() { return ruleCoverage; }
    public void setRuleCoverage(Double ruleCoverage) { this.ruleCoverage = ruleCoverage; }
    public Integer getRuleCount() { return ruleCount; }
    public void setRuleCount(Integer ruleCount) { this.ruleCount = ruleCount; }
    public Integer getEnabledRuleCount() { return enabledRuleCount; }
    public void setEnabledRuleCount(Integer enabledRuleCount) { this.enabledRuleCount = enabledRuleCount; }
    public List<QualityProblemTableVO> getTopProblemTables() { return topProblemTables; }
    public void setTopProblemTables(List<QualityProblemTableVO> topProblemTables) { this.topProblemTables = topProblemTables; }
}
