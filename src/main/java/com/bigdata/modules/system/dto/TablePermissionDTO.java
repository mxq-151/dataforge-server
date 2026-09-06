package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public class TablePermissionDTO {

    private Long id;

    @NotBlank(message = "权限名称不能为空")
    private String permissionName;

    @NotEmpty(message = "数据源不能为空")
    private String datasourceName;
    @NotEmpty(message = "库名不能为空")
    private String databaseName;
    @NotEmpty(message = "表名不能为空")
    private List<String> tableNames;
    private Integer permissionScope;
    private Integer canSelect;
    private Integer canInsert;
    private Integer canUpdate;
    private Integer canDelete;
    private Integer canExport;
    /**
     * 指定行（行级过滤）：表名 -> 行过滤SQL（支持WHERE前缀或裸表达式）。
     * 未在此出现的表表示不做行级过滤。
     */
    private Map<String, String> rowFilterSql;
    private String dataMaskingRule;
    private String description;
    private List<Long> roleIds;
    /**
     * 指定列白名单：表名 -> 允许可见的列名列表。
     * 未在此出现的表表示所有列都可见；表中未列出的列不可见。
     */
    private Map<String, List<String>> columnPermissions;

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

    public Map<String, String> getRowFilterSql() {
        return rowFilterSql;
    }

    public void setRowFilterSql(Map<String, String> rowFilterSql) {
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

    public Map<String, List<String>> getColumnPermissions() {
        return columnPermissions;
    }

    public void setColumnPermissions(Map<String, List<String>> columnPermissions) {
        this.columnPermissions = columnPermissions;
    }
}
