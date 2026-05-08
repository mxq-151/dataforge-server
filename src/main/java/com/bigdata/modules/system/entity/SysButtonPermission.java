package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("sys_button_permission")
public class SysButtonPermission extends BaseEntity {

    private Long menuPermissionId;
    private String buttonName;
    private String buttonCode;
    private String description;
    private Integer sortOrder;

    public Long getMenuPermissionId() {
        return menuPermissionId;
    }

    public void setMenuPermissionId(Long menuPermissionId) {
        this.menuPermissionId = menuPermissionId;
    }

    public String getButtonName() {
        return buttonName;
    }

    public void setButtonName(String buttonName) {
        this.buttonName = buttonName;
    }

    public String getButtonCode() {
        return buttonCode;
    }

    public void setButtonCode(String buttonCode) {
        this.buttonCode = buttonCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
