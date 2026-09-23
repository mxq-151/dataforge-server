package com.bigdata.modules.asset.dto;

import java.io.Serializable;

/**
 * 负责人变更入参。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetOwnerUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务负责人 */
    private String businessOwner;

    /** 技术负责人 */
    private String techOwner;

    public String getBusinessOwner() { return businessOwner; }
    public void setBusinessOwner(String businessOwner) { this.businessOwner = businessOwner; }
    public String getTechOwner() { return techOwner; }
    public void setTechOwner(String techOwner) { this.techOwner = techOwner; }
}
