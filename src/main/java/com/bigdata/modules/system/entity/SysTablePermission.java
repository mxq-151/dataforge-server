package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("sys_table_permission")
public class SysTablePermission extends BaseEntity {

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
}
