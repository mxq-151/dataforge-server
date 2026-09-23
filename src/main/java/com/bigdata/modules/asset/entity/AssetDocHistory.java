package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_doc_history
 *
 * @author dataforge
 */
@TableName("asset_doc_history")
public class AssetDocHistory extends BaseEntity {

    /** 表元数据 ID */
    private Long tableMetadataId;

    /** 版本号 */
    private Integer version;

    /** 该版本的使用说明（markdown） */
    private String content;

    /** 修改人 */
    private String updateBy;

    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
