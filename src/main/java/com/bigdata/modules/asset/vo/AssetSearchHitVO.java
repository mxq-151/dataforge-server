package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产检索命中项。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetSearchHitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 命中资产类型：table / column / indicator */
    private String assetType;

    /** table / indicator 用自身 id；column 用所属表 id */
    private Long id;

    /** 命中的表元数据 id */
    private Long tableMetadataId;

    /** 标题 */
    private String title;

    /** 副标题：库.表 / 表.字段 / 指标编码 */
    private String subtitle;

    /** 命中片段，用于列表高亮 */
    private String matchedText;

    /** 负责人 */
    private String ownerName;

    /** 分层编码 */
    private String layerCode;

    /** 业务域编码 */
    private String domainCode;

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMatchedText() { return matchedText; }
    public void setMatchedText(String matchedText) { this.matchedText = matchedText; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getLayerCode() { return layerCode; }
    public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
}
