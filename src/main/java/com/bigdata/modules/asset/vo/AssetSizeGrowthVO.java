package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 数据大小增长率突增表。
 *
 * <p>字段名与前端 {@code AssetSizeGrowthVO} 逐字对应。</p>
 *
 * @author dataforge
 */
public class AssetSizeGrowthVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表元数据主键 */
    private Long tableMetadataId;

    private String databaseName;
    private String tableName;

    /** 环境码：prod / gray / test */
    private String env;

    /** 项目显示名；未归属任何项目时为「未归属项目」 */
    private String projectName;

    /** 上期大小（字节） */
    private Long previousSizeBytes;

    /** 本期大小（字节） */
    private Long currentSizeBytes;

    /** 增长量（字节） */
    private Long growthBytes;

    /** 增长率 0~1 小数，(current - previous) / previous */
    private Double growthRate;

    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Long getPreviousSizeBytes() { return previousSizeBytes; }
    public void setPreviousSizeBytes(Long previousSizeBytes) { this.previousSizeBytes = previousSizeBytes; }
    public Long getCurrentSizeBytes() { return currentSizeBytes; }
    public void setCurrentSizeBytes(Long currentSizeBytes) { this.currentSizeBytes = currentSizeBytes; }
    public Long getGrowthBytes() { return growthBytes; }
    public void setGrowthBytes(Long growthBytes) { this.growthBytes = growthBytes; }
    public Double getGrowthRate() { return growthRate; }
    public void setGrowthRate(Double growthRate) { this.growthRate = growthRate; }
}
