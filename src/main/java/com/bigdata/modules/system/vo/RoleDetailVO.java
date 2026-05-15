package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.util.List;

public class RoleDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private SysRoleVO role;
    private List<Long> userIds;

    public RoleDetailVO() {
    }

    public RoleDetailVO(SysRoleVO role, List<Long> userIds) {
        this.role = role;
        this.userIds = userIds;
    }

    public SysRoleVO getRole() {
        return role;
    }

    public void setRole(SysRoleVO role) {
        this.role = role;
    }

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds;
    }
}
