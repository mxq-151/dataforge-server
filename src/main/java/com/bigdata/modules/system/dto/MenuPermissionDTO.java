package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class MenuPermissionDTO {

    private Long id;

    @NotBlank(message = "权限名称不能为空")
    private String permissionName;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 100, message = "菜单名称不能超过100字符")
    private String menuName;

    @NotBlank(message = "菜单编码不能为空")
    @Size(max = 100, message = "菜单编码不能超过100字符")
    private String menuCode;


    private Integer visible;
    private Integer status;
    private List<Long> roleIds;

     public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuCode() {
        return menuCode;
    }

    public void setMenuCode(String menuCode) {
        this.menuCode = menuCode;
    }

    public Integer getVisible() {
        return visible;
    }

    public void setVisible(Integer visible) {
        this.visible = visible;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
