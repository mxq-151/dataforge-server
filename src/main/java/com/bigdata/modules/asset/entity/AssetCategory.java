package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_category
 *
 * @author dataforge
 */
@TableName("asset_category")
public class AssetCategory extends BaseEntity {

    /** 分类编码（分层/业务域编码） */
    private String categoryCode;

    /** 分类名称 */
    private String categoryName;

    /** 父级分类编码 */
    private String parentCode;

    /** 分类维度 layer=数仓分层 domain=业务域 */
    private String categoryKind;

    /** 排序号 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public String getCategoryKind() { return categoryKind; }
    public void setCategoryKind(String categoryKind) { this.categoryKind = categoryKind; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
