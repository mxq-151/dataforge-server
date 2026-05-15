package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotNull;

public class ProjectPermissionDTO {

    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "权限类型不能为空")
    private Integer permissionType;

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Integer getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(Integer permissionType) {
        this.permissionType = permissionType;
    }
}
