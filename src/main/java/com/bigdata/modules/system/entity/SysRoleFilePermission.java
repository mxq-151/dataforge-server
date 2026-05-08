package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_role_file_permission")
public class SysRoleFilePermission {

    private Long id;
    private Long roleId;
    private Long filePermissionId;
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

    public Long getFilePermissionId() {
        return filePermissionId;
    }

    public void setFilePermissionId(Long filePermissionId) {
        this.filePermissionId = filePermissionId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
