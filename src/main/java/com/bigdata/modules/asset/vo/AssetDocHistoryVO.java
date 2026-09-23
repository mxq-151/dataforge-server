package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产使用说明的历史版本条目。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的 {@code listDocHistory()} 契约逐字对应
 * （{@code { version, updateTime, updateBy }}），改动需两端同步。
 * 刻意不返回历史正文：历史列表只用于「谁在什么时候改过」，
 * 把每个版本的全文一起传出去会让响应体随文档长度线性膨胀。</p>
 *
 * @author dataforge
 */
public class AssetDocHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 版本号 */
    private Integer version;

    /** 该版本的修改时间 */
    private String updateTime;

    /** 该版本的修改人 */
    private String updateBy;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
