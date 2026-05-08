package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public class RoleDTO {

    private Long id;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称不能超过50字符")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 50, message = "角色编码不能超过50字符")
    private String roleCode;

    @Size(max = 255, message = "角色描述不能超过255字符")
    private String description;

    private Integer status;
    
    private List<Long> menuPermissionIds;
    private List<Long> userIds;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<Long> getMenuPermissionIds() {
        return menuPermissionIds;
    }

    public void setMenuPermissionIds(List<Long> menuPermissionIds) {
        this.menuPermissionIds = menuPermissionIds;
    }

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds;
    }
}
