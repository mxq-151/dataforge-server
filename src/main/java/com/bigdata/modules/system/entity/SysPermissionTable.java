package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("sys_permission_table")
public class SysPermissionTable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tablePermissionId;
    private Long tableMetadataId;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTablePermissionId() {
        return tablePermissionId;
    }

    public void setTablePermissionId(Long tablePermissionId) {
        this.tablePermissionId = tablePermissionId;
    }

    public Long getTableMetadataId() {
        return tableMetadataId;
    }

    public void setTableMetadataId(Long tableMetadataId) {
        this.tableMetadataId = tableMetadataId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
