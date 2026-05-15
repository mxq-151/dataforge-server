package com.bigdata.modules.system.dto;


import jakarta.validation.constraints.NotNull;

public class MenuRoleBindDTO {

    @NotNull(message = "菜单ID不能为空")
    private Long menuId;

    @NotNull(message = "角色ID列表不能为空")
    private Long roleId;

    public Long getMenuId() {
        return menuId;
    }

    public void setMenuId(Long menuId) {
        this.menuId = menuId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleIds(Long roleId) {
        this.roleId = roleId;
    }
}
