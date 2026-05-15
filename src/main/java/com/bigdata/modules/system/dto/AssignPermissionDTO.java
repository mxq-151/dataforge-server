package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public class AssignPermissionDTO {

    @NotNull(message = "菜单权限ID列表不能为null")
    private List<Long> menuPermissionIds;

    @NotNull(message = "文件权限ID列表不能为null")
    private List<Long> filePermissionIds;

    @NotNull(message = "表权限ID列表不能为null")
    private List<Long> tablePermissionIds;

    public List<Long> getMenuPermissionIds() {
        return menuPermissionIds;
    }

    public void setMenuPermissionIds(List<Long> menuPermissionIds) {
        this.menuPermissionIds = menuPermissionIds;
    }

    public List<Long> getFilePermissionIds() {
        return filePermissionIds;
    }

    public void setFilePermissionIds(List<Long> filePermissionIds) {
        this.filePermissionIds = filePermissionIds;
    }

    public List<Long> getTablePermissionIds() {
        return tablePermissionIds;
    }

    public void setTablePermissionIds(List<Long> tablePermissionIds) {
        this.tablePermissionIds = tablePermissionIds;
    }
}
