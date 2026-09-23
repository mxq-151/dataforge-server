package com.bigdata.modules.quality.vo;


/**
 * QualityPassRatePointVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityPassRatePointVO {

    /** 统计日 */
    private String statDate;

    /** 通过率 0~1 */
    private Double passRate;

    /** 未通过条数 */
    private Integer failCount;

    public String getStatDate() { return statDate; }
    public void setStatDate(String statDate) { this.statDate = statDate; }
    public Double getPassRate() { return passRate; }
    public void setPassRate(Double passRate) { this.passRate = passRate; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
}
