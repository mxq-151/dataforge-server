package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String roleName;
    private String roleCode;
    private String description;
    private Integer status;

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
