package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public class FilePermissionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long storageConfigId;
    private String permissionName;
    private String filePath;
    private Integer permissionType;
    private Integer recursive;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<Long> roleIds;
    private List<String> roleNames;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStorageConfigId() {
        return storageConfigId;
    }

    public void setStorageConfigId(Long storageConfigId) {
        this.storageConfigId = storageConfigId;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }

    public List<String> getRoleNames() {
        return roleNames;
    }

    public void setRoleNames(List<String> roleNames) {
        this.roleNames = roleNames;
    }
}
