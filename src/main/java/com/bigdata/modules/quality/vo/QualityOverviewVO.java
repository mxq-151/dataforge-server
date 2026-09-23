package com.bigdata.modules.quality.vo;

import com.bigdata.modules.quality.vo.QualityPassRatePointVO;
import com.bigdata.modules.quality.vo.QualityProblemTableVO;
import java.util.List;

/**
 * QualityOverviewVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
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

    /** 近 N 日通过率趋势 */
    private List<QualityPassRatePointVO> passRateTrend;

    /** TOP 质量问题表 */
    private List<QualityProblemTableVO> topProblemTables;

    /** 强规则阻断次数 */
    private Integer blockCount;

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
    public List<QualityPassRatePointVO> getPassRateTrend() { return passRateTrend; }
    public void setPassRateTrend(List<QualityPassRatePointVO> passRateTrend) { this.passRateTrend = passRateTrend; }
    public List<QualityProblemTableVO> getTopProblemTables() { return topProblemTables; }
    public void setTopProblemTables(List<QualityProblemTableVO> topProblemTables) { this.topProblemTables = topProblemTables; }
    public Integer getBlockCount() { return blockCount; }
    public void setBlockCount(Integer blockCount) { this.blockCount = blockCount; }
}
