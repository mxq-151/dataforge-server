package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

import java.time.LocalDate;

/**
 * table_partition_size_daily —— 分区日大小（一行 = 一张天分区表 × 一个统计日）。
 *
 * <h3>为什么单独一张表</h3>
 * <p>全量表的存储大小每天被 {@code asset_catalog_daily.size_bytes} 覆盖记录，直接做环比即可；
 * 而天分区表的整表大小天然只增不减（按天追加分区），整表对比永远显示「增长」，
 * 看不出「某一天分区异常膨胀」。本表按天记录该表<b>当日最新分区</b>的值与大小，
 * 累积多天后即可算「近 N 天分区均值」，用于识别单日分区突增。</p>
 *
 * <h3>为什么只存最新分区</h3>
 * <p>增长率关注的是「最近一天的分区是否明显大于近期常态」。每日采一条最新分区，
 * 连续多日即形成一条按天的分区大小序列，足够计算均值与突增，且数据量与表数同阶、
 * 远小于「每日记录全部分区」。分区全量明细属于另一个量级的需求，不在此表承担。</p>
 *
 * @author dataforge
 */
@TableName("table_partition_size_daily")
public class AssetPartitionSizeDaily extends BaseEntity {

    /** 统计日 */
    private LocalDate statDate;

    /** 表元数据 ID */
    private Long tableMetadataId;

    private Long datasourceId;
    private String databaseName;
    private String tableName;

    /** 命中的日期类分区列名（如 dt / ds） */
    private String partitionColumn;

    /** 当日最新分区值（如 2026-10-09 / 20261009） */
    private String partitionValue;

    /** 该分区存储字节数 */
    private Long sizeBytes;

    /** 该分区行数；取不到为 null */
    private Long rowCount;

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getPartitionColumn() { return partitionColumn; }
    public void setPartitionColumn(String partitionColumn) { this.partitionColumn = partitionColumn; }
    public String getPartitionValue() { return partitionValue; }
    public void setPartitionValue(String partitionValue) { this.partitionValue = partitionValue; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
}
