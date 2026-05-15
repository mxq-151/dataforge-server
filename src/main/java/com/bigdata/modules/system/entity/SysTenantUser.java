package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_tenant_user")
public class SysTenantUser {

    private Long id;
    private Long tenantId;
    private Long userId;
    private java.time.LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public java.time.LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(java.time.LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
