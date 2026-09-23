package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产分类项（分层 / 业务域）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的 {@code listCategories()} 契约逐字对应
 * （{@code { code, name, parentCode, categoryKind }}），改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类编码 */
    private String code;

    /** 分类名称 */
    private String name;

    /** 父级分类编码 */
    private String parentCode;

    /** 分类维度：layer=数仓分层 domain=业务域 */
    private String categoryKind;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public String getCategoryKind() { return categoryKind; }
    public void setCategoryKind(String categoryKind) { this.categoryKind = categoryKind; }
}
