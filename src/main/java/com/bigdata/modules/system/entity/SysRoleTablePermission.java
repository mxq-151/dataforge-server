package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_role_table_permission")
public class SysRoleTablePermission {

    private Long id;
    private Long roleId;
    private Long tablePermissionId;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getTablePermissionId() {
        return tablePermissionId;
    }

    public void setTablePermissionId(Long tablePermissionId) {
        this.tablePermissionId = tablePermissionId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
