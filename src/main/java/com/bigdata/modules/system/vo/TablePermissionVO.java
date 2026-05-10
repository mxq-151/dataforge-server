package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public class TablePermissionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private List<Long> tableMetadataIds;
    private String datasourceName;
    private String databaseName;
    private List<String> tableNames;
    private String permissionName;
    private Integer permissionScope;
    private Integer canSelect;
    private Integer canInsert;
    private Integer canUpdate;
    private Integer canDelete;
    private Integer canExport;
    private String rowFilterSql;
    private String dataMaskingRule;
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

    public List<Long> getTableMetadataIds() {
        return tableMetadataIds;
    }

    public void setTableMetadataIds(List<Long> tableMetadataIds) {
        this.tableMetadataIds = tableMetadataIds;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public void setDatasourceName(String datasourceName) {
        this.datasourceName = datasourceName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public void setTableNames(List<String> tableNames) {
        this.tableNames = tableNames;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public Integer getPermissionScope() {
        return permissionScope;
    }

    public void setPermissionScope(Integer permissionScope) {
        this.permissionScope = permissionScope;
    }

    public Integer getCanSelect() {
        return canSelect;
    }

    public void setCanSelect(Integer canSelect) {
        this.canSelect = canSelect;
    }

    public Integer getCanInsert() {
        return canInsert;
    }

    public void setCanInsert(Integer canInsert) {
        this.canInsert = canInsert;
    }

    public Integer getCanUpdate() {
        return canUpdate;
    }

    public void setCanUpdate(Integer canUpdate) {
        this.canUpdate = canUpdate;
    }

    public Integer getCanDelete() {
        return canDelete;
    }

    public void setCanDelete(Integer canDelete) {
        this.canDelete = canDelete;
    }

    public Integer getCanExport() {
        return canExport;
    }

    public void setCanExport(Integer canExport) {
        this.canExport = canExport;
    }

    public String getRowFilterSql() {
        return rowFilterSql;
    }

    public void setRowFilterSql(String rowFilterSql) {
        this.rowFilterSql = rowFilterSql;
    }

    public String getDataMaskingRule() {
        return dataMaskingRule;
    }

    public void setDataMaskingRule(String dataMaskingRule) {
        this.dataMaskingRule = dataMaskingRule;
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
