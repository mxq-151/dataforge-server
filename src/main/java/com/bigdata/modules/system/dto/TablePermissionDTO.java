package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class TablePermissionDTO {

    private Long id;

    @NotBlank(message = "权限名称不能为空")
    private String permissionName;

    @NotEmpty(message = "表元数据ID不能为空")
    private List<Long> tableMetadataIds;

    private Integer permissionScope;
    private Integer canSelect;
    private Integer canInsert;
    private Integer canUpdate;
    private Integer canDelete;
    private Integer canExport;
    private String rowFilterSql;
    private String dataMaskingRule;
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

    public List<Long> getTableMetadataIds() {
        return tableMetadataIds;
    }

    public void setTableMetadataIds(List<Long> tableMetadataIds) {
        this.tableMetadataIds = tableMetadataIds;
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

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
