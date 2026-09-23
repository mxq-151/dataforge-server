package com.bigdata.modules.asset.dto;

import java.io.Serializable;

/**
 * 使用说明保存入参。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetDocSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** markdown 正文 */
    private String content;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
