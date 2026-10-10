package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 资产盘点。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetInventoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表总数 */
    private Integer totalTables;

    /** 存储总量（字节） */
    private Long totalSizeBytes;

    /** 字段总数 */
    private Integer totalColumns;

    /** 按分层统计 */
    private List<LayerStat> byLayer;

    /** 按业务域统计 */
    private List<DomainStat> byDomain;

    /** 按数据源统计 */
    private List<DatasourceStat> byDatasource;

    /**
     * 质检规划覆盖率（0~1）—— 范围内「已配置质检规则」的表占「应配置质检规则」的表比例。
     *
     * <p>分母为能解析出数仓分层、且非质检层 {@code dqc} 的表；分子是其中已启用至少一条
     * 质检规则的表。无应配置表时为 null（前端展示为「—」）。</p>
     */
    private Double qualityCoverageRate;

    public Integer getTotalTables() { return totalTables; }
    public void setTotalTables(Integer totalTables) { this.totalTables = totalTables; }
    public Long getTotalSizeBytes() { return totalSizeBytes; }
    public void setTotalSizeBytes(Long totalSizeBytes) { this.totalSizeBytes = totalSizeBytes; }
    public Integer getTotalColumns() { return totalColumns; }
    public void setTotalColumns(Integer totalColumns) { this.totalColumns = totalColumns; }
    public List<LayerStat> getByLayer() { return byLayer; }
    public void setByLayer(List<LayerStat> byLayer) { this.byLayer = byLayer; }
    public List<DomainStat> getByDomain() { return byDomain; }
    public void setByDomain(List<DomainStat> byDomain) { this.byDomain = byDomain; }
    public List<DatasourceStat> getByDatasource() { return byDatasource; }
    public void setByDatasource(List<DatasourceStat> byDatasource) { this.byDatasource = byDatasource; }
    public Double getQualityCoverageRate() { return qualityCoverageRate; }
    public void setQualityCoverageRate(Double qualityCoverageRate) { this.qualityCoverageRate = qualityCoverageRate; }

    /**
     * 分层统计项。
     */
    public static class LayerStat implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 分层编码 */
        private String layerCode;

        /** 表数 */
        private Integer tableCount;

        /** 存储量 */
        private Long sizeBytes;

        /** 该分层的质检规划覆盖率（0~1）；无应配置表时为 null */
        private Double qualityCoverageRate;

        public String getLayerCode() { return layerCode; }
        public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
        public Integer getTableCount() { return tableCount; }
        public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
        public Double getQualityCoverageRate() { return qualityCoverageRate; }
        public void setQualityCoverageRate(Double qualityCoverageRate) { this.qualityCoverageRate = qualityCoverageRate; }
    }

    /**
     * 业务域统计项。
     */
    public static class DomainStat implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 业务域编码 */
        private String domainCode;

        /** 表数 */
        private Integer tableCount;

        /** 存储量 */
        private Long sizeBytes;

        /** 该业务域（数仓）的质检规划覆盖率（0~1）；无应配置表时为 null */
        private Double qualityCoverageRate;

        public String getDomainCode() { return domainCode; }
        public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
        public Integer getTableCount() { return tableCount; }
        public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
        public Double getQualityCoverageRate() { return qualityCoverageRate; }
        public void setQualityCoverageRate(Double qualityCoverageRate) { this.qualityCoverageRate = qualityCoverageRate; }
    }

    /**
     * 数据源统计项。
     */
    public static class DatasourceStat implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 数据源 id */
        private Long datasourceId;

        /** 数据源名称 */
        private String datasourceName;

        /** 表数 */
        private Integer tableCount;

        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
        public String getDatasourceName() { return datasourceName; }
        public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
        public Integer getTableCount() { return tableCount; }
        public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    }
}
