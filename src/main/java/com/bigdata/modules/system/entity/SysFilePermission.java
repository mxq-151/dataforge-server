package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("sys_file_permission")
public class SysFilePermission extends BaseEntity {

    private Long storageConfigId;
    private String permissionName;
    private String filePath;
    private Integer permissionType;

    /**
     * 是否递归子目录。
     * <p>列名用 {@code is_recursive}：RECURSIVE 是 MySQL 8 的保留字，而 MySQL 的反引号
     * 在 PostgreSQL/H2 下是非法语法，同一个字面量无法同时兼容；改用非保留字后各方言
     * 都能用裸列名。</p>
     */
    @TableField("is_recursive")
    private Integer recursive;

    private String description;

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
}
