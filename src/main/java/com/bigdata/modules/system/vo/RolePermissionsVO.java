package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.util.List;

public class RolePermissionsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<Long> menuPermissionIds;
    private List<Long> filePermissionIds;
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
