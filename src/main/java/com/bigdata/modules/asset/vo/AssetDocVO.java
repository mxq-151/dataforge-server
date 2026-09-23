package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 资产使用说明（markdown 正文 + 版本信息）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的 {@code getTableDoc()} 契约逐字对应
 * （{@code { content, updateTime, updateBy }}），改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** markdown 正文 */
    private String content;

    /** 最后修改时间 */
    private String updateTime;

    /** 最后修改人 */
    private String updateBy;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
