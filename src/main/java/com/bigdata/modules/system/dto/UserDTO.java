package com.bigdata.modules.system.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public class UserDTO {

    private Long id;

    @NotBlank(message = "用户名不能为空", groups = {Create.class})
    @Size(min = 3, max = 50, message = "用户名长度必须在3-50字符之间")
    private String username;

    @NotBlank(message = "密码不能为空", groups = {Create.class})
    @Size(min = 6, max = 20, message = "密码长度必须在6-20位之间", groups = {Create.class})
    @Size(min = 6, max = 20, message = "密码长度必须在6-20位之间", groups = {Update.class})
    private String password;

    @Size(max = 50, message = "真实姓名不能超过50字符")
    private String realName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100)
    private String email;

    @Size(max = 20, message = "手机号不能超过20字符")
    private String phone;

    @Size(max = 255)
    private String avatar;

    private Integer status;
    
    private List<Long> roleIds;

    public interface Create {}
    public interface Update {}

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
