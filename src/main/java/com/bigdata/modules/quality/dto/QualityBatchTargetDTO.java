package com.bigdata.modules.quality.dto;


/**
 * QualityBatchTargetDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityBatchTargetDTO {

    /** 数据源 ID */
    private Long datasourceId;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 字段名 */
    private String columnName;

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
}
