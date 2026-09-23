package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_tag
 *
 * @author dataforge
 */
@TableName("asset_tag")
public class AssetTag extends BaseEntity {

    /** 标签编码 */
    private String tagCode;

    /** 标签名称 */
    private String tagName;

    /** 标签颜色（前端色值） */
    private String color;

    /** 备注 */
    private String remark;

    /** 是否启用 */
    private Integer enabled;

    public String getTagCode() { return tagCode; }
    public void setTagCode(String tagCode) { this.tagCode = tagCode; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
