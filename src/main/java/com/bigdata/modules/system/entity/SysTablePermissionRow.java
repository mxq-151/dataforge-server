package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 表权限的行级过滤（指定行）：按 (table_permission_id, table_name) 存储各自的行过滤SQL。
 * <p>
 * 一条表权限可覆盖多张表，每张表可配置不同的行过滤条件。
 * 某张表在该条权限下不存在记录时，表示该表不做行级过滤（所有行可见）。
 * </p>
 */
@TableName("sys_table_permission_row")
public class SysTablePermissionRow {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tablePermissionId;
    private String tableName;
    private String rowFilterSql;
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

    public String getRowFilterSql() {
        return rowFilterSql;
    }

    public void setRowFilterSql(String rowFilterSql) {
        this.rowFilterSql = rowFilterSql;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}