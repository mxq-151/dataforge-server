package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 统一检索结果（表 / 字段 / 指标三类）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetSearchResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 命中的表 */
    private List<AssetSearchHitVO> tables;

    /** 命中的字段 */
    private List<AssetSearchHitVO> columns;

    /** 命中的指标 */
    private List<AssetSearchHitVO> indicators;

    /** 命中总数（三类合计） */
    private Integer total;

    public List<AssetSearchHitVO> getTables() { return tables; }
    public void setTables(List<AssetSearchHitVO> tables) { this.tables = tables; }
    public List<AssetSearchHitVO> getColumns() { return columns; }
    public void setColumns(List<AssetSearchHitVO> columns) { this.columns = columns; }
    public List<AssetSearchHitVO> getIndicators() { return indicators; }
    public void setIndicators(List<AssetSearchHitVO> indicators) { this.indicators = indicators; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
}
