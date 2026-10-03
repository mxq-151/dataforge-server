package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 按环境与项目聚合的资产规模。
 *
 * <p>粒度即「环境 × 项目」一行，环境汇总与全库合计由前端就地聚合，
 * 不再单开一个环境汇总接口。字段名与前端 {@code AssetEnvProjectStatVO} 逐字对应。</p>
 *
 * @author dataforge
 */
public class AssetEnvProjectStatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 环境码：prod / gray / test */
    private String env;

    /** 项目编码；未归属任何项目时为空串 */
    private String projectCode;

    /** 项目显示名；未归属任何项目时为「未归属项目」 */
    private String projectName;

    /** 该环境 × 项目下的表数量 */
    private Integer tableCount;

    /** 占用空间（字节） */
    private Long sizeBytes;

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Integer getTableCount() { return tableCount; }
    public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
}
