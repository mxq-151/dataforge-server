package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * TOP 资产清单项（按占用空间排序）。
 *
 * <p>字段名与前端 {@code AssetTopAssetVO} 逐字对应。</p>
 *
 * @author dataforge
 */
public class AssetTopAssetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表元数据主键，可作为详情跳转键 */
    private Long tableMetadataId;

    private String databaseName;
    private String tableName;

    /** 环境码：prod / gray / test */
    private String env;

    /** 项目编码；未归属任何项目时为空串 */
    private String projectCode;

    /** 项目显示名；未归属任何项目时为「未归属项目」 */
    private String projectName;

    /** 占用空间（字节） */
    private Long sizeBytes;

    /** 负责人；无则为空串 */
    private String ownerName;

    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
}
