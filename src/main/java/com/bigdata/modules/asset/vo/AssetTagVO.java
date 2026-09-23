package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产标签。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetTagVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签 id */
    private Long id;

    /** 标签编码 */
    private String tagCode;

    /** 标签名 */
    private String tagName;

    /** 标签颜色 */
    private String color;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTagCode() { return tagCode; }
    public void setTagCode(String tagCode) { this.tagCode = tagCode; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
}
