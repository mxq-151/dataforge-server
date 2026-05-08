package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_role_menu_permission")
public class SysRoleMenuPermission {

    private Long id;
    private Long roleId;
    private Long menuPermissionId;
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

    public Long getMenuPermissionId() {
        return menuPermissionId;
    }

    public void setMenuPermissionId(Long menuPermissionId) {
        this.menuPermissionId = menuPermissionId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
