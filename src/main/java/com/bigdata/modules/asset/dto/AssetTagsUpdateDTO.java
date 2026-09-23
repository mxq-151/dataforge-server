package com.bigdata.modules.asset.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 标签设置入参（整体替换）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetTagsUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签编码列表；空列表表示清空标签 */
    private List<String> tagCodes;

    public List<String> getTagCodes() { return tagCodes; }
    public void setTagCodes(List<String> tagCodes) { this.tagCodes = tagCodes; }
}
