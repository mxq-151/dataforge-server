package com.bigdata.modules.system.vo;

import com.bigdata.common.security.DataAccessPermissionService;

import java.io.Serializable;

public class ProjectPermissionInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String projectCode;
    private Integer permissionType;
    private String permissionTypeName;

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public Integer getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(Integer permissionType) {
        this.permissionType = permissionType;
    }

    public String getPermissionTypeName() {
        return permissionTypeName;
    }

    public void setPermissionTypeName(String permissionTypeName) {
        this.permissionTypeName = permissionTypeName;
    }

    /**
     * 权限类型 -> 名称映射(与 DataAccessPermissionService 保持一致)
     */
    public static String permissionTypeName(Integer permissionType) {
        if (permissionType == null) {
            return "访问";
        }
        return switch (permissionType) {
            case DataAccessPermissionService.PROJECT_PERMISSION_READ -> "读取";
            case DataAccessPermissionService.PROJECT_PERMISSION_EDIT -> "编辑";
            case DataAccessPermissionService.PROJECT_PERMISSION_DELETE -> "删除";
            default -> "访问";
        };
    }
}