package com.bigdata.modules.system.dto;

import java.util.List;

public class BatchProjectPermissionDTO {

    private Long roleId;
    private List<ProjectPermissionItem> items;

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public List<ProjectPermissionItem> getItems() {
        return items;
    }

    public void setItems(List<ProjectPermissionItem> items) {
        this.items = items;
    }

    public static class ProjectPermissionItem {
        private Long projectId;
        private Integer permissionType;

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
}
