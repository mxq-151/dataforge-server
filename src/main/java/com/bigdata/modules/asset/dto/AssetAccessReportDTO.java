package com.bigdata.modules.asset.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 访问埋点上报入参。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetAccessReportDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 埋点项列表 */
    private List<AccessItem> items;

    public List<AccessItem> getItems() { return items; }
    public void setItems(List<AccessItem> items) { this.items = items; }

    /**
     * 单条埋点。
     */
    public static class AccessItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 资产类型：table / column / indicator */
        private String assetType;

        /** 资产 id */
        private Long assetId;

        /** 访问类型：browse / read / bookmark / query */
        private String accessType;

        /** 带查询时的关键词 */
        private String keyword;

        public String getAssetType() { return assetType; }
        public void setAssetType(String assetType) { this.assetType = assetType; }
        public Long getAssetId() { return assetId; }
        public void setAssetId(Long assetId) { this.assetId = assetId; }
        public String getAccessType() { return accessType; }
        public void setAccessType(String accessType) { this.accessType = accessType; }
        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
    }
}
