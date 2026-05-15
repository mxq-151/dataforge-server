package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.util.List;

public class UserInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private SysUserVO user;
    private List<Long> roleIds;

    public UserInfoVO() {
    }

    public UserInfoVO(SysUserVO user, List<Long> roleIds) {
        this.user = user;
        this.roleIds = roleIds;
    }

    public SysUserVO getUser() {
        return user;
    }

    public void setUser(SysUserVO user) {
        this.user = user;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
