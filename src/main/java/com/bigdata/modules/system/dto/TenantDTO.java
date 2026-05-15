package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TenantDTO {

    private Long id;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 100, message = "租户名称不能超过100字符")
    private String tenantName;

    @NotBlank(message = "租户编码不能为空")
    @Size(max = 50, message = "租户编码不能超过50字符")
    private String tenantCode;

    @Size(max = 50, message = "联系人姓名不能超过50字符")
    private String contactName;

    @Size(max = 20, message = "联系电话不能超过20字符")
    private String contactPhone;

    @Size(max = 100, message = "联系邮箱不能超过100字符")
    private String contactEmail;

    @Size(max = 500, message = "描述不能超过500字符")
    private String description;

    private Integer status;

    private Long userId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
