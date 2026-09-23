package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_doc
 *
 * @author dataforge
 */
@TableName("asset_doc")
public class AssetDoc extends BaseEntity {

    /** 表元数据 ID */
    private Long tableMetadataId;

    /** 使用说明（markdown） */
    private String content;

    /** 当前版本号 */
    private Integer version;

    /** 最后修改人 */
    private String updateBy;

    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
