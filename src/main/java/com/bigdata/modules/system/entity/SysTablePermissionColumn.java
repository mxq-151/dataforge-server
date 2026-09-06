package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 表权限的列级白名单（指定列）。
 * <p>
 * 一条表权限可覆盖多张表，每张表允许可见的列集合以 (table_permission_id, table_name) 为单位逐列存储。
 * 某张表在该条权限下不存在任何列记录时，表示该表所有列都可见（即未做列限制）。
 * </p>
 */
@TableName("sys_table_permission_column")
public class SysTablePermissionColumn {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tablePermissionId;
    private String tableName;
    private String columnName;
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

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}