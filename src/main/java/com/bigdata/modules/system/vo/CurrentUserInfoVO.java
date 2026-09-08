package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.util.List;

public class CurrentUserInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String realName;
    private String email;
    private String phone;
    private String avatar;
    private List<SysRoleVO> roles;
    private List<String> menuTags;
    private List<ProjectPermissionInfoVO> projectCodes;
    private Long tenantId;
    private String tenantName;

    public CurrentUserInfoVO() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public List<SysRoleVO> getRoles() {
        return roles;
    }

    public void setRoles(List<SysRoleVO> roles) {
        this.roles = roles;
    }

    public List<String> getMenuTags() {
        return menuTags;
    }

    public void setMenuTags(List<String> menuTags) {
        this.menuTags = menuTags;
    }

    public List<ProjectPermissionInfoVO> getProjectCodes() {
        return projectCodes;
    }

    public void setProjectCodes(List<ProjectPermissionInfoVO> projectCodes) {
        this.projectCodes = projectCodes;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }
}
