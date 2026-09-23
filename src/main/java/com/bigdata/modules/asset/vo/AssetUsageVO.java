package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 资产使用记录（热度趋势 + 字段热度 + 高频读者）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetUsageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 近 N 日浏览/读取趋势 */
    private List<BrowseTrendPoint> browseTrend;

    /** 字段热度 TOP */
    private List<ColumnHot> topColumns;

    /** 高频读者 TOP */
    private List<TopReader> topReaders;

    public List<BrowseTrendPoint> getBrowseTrend() { return browseTrend; }
    public void setBrowseTrend(List<BrowseTrendPoint> browseTrend) { this.browseTrend = browseTrend; }
    public List<ColumnHot> getTopColumns() { return topColumns; }
    public void setTopColumns(List<ColumnHot> topColumns) { this.topColumns = topColumns; }
    public List<TopReader> getTopReaders() { return topReaders; }
    public void setTopReaders(List<TopReader> topReaders) { this.topReaders = topReaders; }

    /**
     * 单日浏览与读取次数。
     */
    public static class BrowseTrendPoint implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 统计日期 */
        private String statDate;

        /** 浏览次数 */
        private Integer browseCount;

        /** 读取次数 */
        private Integer readCount;

        public String getStatDate() { return statDate; }
        public void setStatDate(String statDate) { this.statDate = statDate; }
        public Integer getBrowseCount() { return browseCount; }
        public void setBrowseCount(Integer browseCount) { this.browseCount = browseCount; }
        public Integer getReadCount() { return readCount; }
        public void setReadCount(Integer readCount) { this.readCount = readCount; }
    }

    /**
     * 字段热度项。
     */
    public static class ColumnHot implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 字段名 */
        private String columnName;

        /** 热度星级 0~5 */
        private Integer hotScore;

        /** 命中次数 */
        private Integer hitCount;

        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public Integer getHotScore() { return hotScore; }
        public void setHotScore(Integer hotScore) { this.hotScore = hotScore; }
        public Integer getHitCount() { return hitCount; }
        public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }
    }

    /**
     * 高频读者项。
     */
    public static class TopReader implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用户名 */
        private String userName;

        /** 读取次数 */
        private Integer readCount;

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public Integer getReadCount() { return readCount; }
        public void setReadCount(Integer readCount) { this.readCount = readCount; }
    }
}
