package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class FilePermissionDTO {

    private Long id;

    @NotBlank(message = "权限名称不能为空")
    private String permissionName;

    @NotNull(message = "存储配置不能为空")
    private Long storageConfigId;

    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    private Integer permissionType;
    private Integer recursive;
    private String description;
    private List<Long> roleIds;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public Long getStorageConfigId() {
        return storageConfigId;
    }

    public void setStorageConfigId(Long storageConfigId) {
        this.storageConfigId = storageConfigId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(Integer permissionType) {
        this.permissionType = permissionType;
    }

    public Integer getRecursive() {
        return recursive;
    }

    public void setRecursive(Integer recursive) {
        this.recursive = recursive;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
