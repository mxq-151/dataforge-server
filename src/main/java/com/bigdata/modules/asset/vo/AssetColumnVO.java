package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产详情的字段项。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetColumnVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 字段元数据 id */
    private Long id;

    /** 字段名 */
    private String columnName;

    /** 字段类型 */
    private String columnType;

    /** 字段注释（技术侧） */
    private String columnComment;

    /** 业务描述（治理补充） */
    private String businessDesc;

    /** 是否分区字段 */
    private Boolean isPartition;

    /** 是否主键 */
    private Boolean isPrimaryKey;

    /** 是否可空 */
    private Boolean nullable;

    /** 字段序号 */
    private Integer ordinalPosition;

    /** 安全等级 */
    private String securityLevel;

    /** 字段热度星级 0~5 */
    private Integer hotScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getColumnType() { return columnType; }
    public void setColumnType(String columnType) { this.columnType = columnType; }
    public String getColumnComment() { return columnComment; }
    public void setColumnComment(String columnComment) { this.columnComment = columnComment; }
    public String getBusinessDesc() { return businessDesc; }
    public void setBusinessDesc(String businessDesc) { this.businessDesc = businessDesc; }
    public Boolean getIsPartition() { return isPartition; }
    public void setIsPartition(Boolean isPartition) { this.isPartition = isPartition; }
    public Boolean getIsPrimaryKey() { return isPrimaryKey; }
    public void setIsPrimaryKey(Boolean isPrimaryKey) { this.isPrimaryKey = isPrimaryKey; }
    public Boolean getNullable() { return nullable; }
    public void setNullable(Boolean nullable) { this.nullable = nullable; }
    public Integer getOrdinalPosition() { return ordinalPosition; }
    public void setOrdinalPosition(Integer ordinalPosition) { this.ordinalPosition = ordinalPosition; }
    public String getSecurityLevel() { return securityLevel; }
    public void setSecurityLevel(String securityLevel) { this.securityLevel = securityLevel; }
    public Integer getHotScore() { return hotScore; }
    public void setHotScore(Integer hotScore) { this.hotScore = hotScore; }
}
