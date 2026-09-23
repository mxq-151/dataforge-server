package com.bigdata.modules.quality.vo;


/**
 * QualityProblemTableVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityProblemTableVO {

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 未通过次数 */
    private Integer failCount;

    /** 责任人 */
    private String ownerName;

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
}
