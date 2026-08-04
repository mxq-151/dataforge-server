package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.AesUtils;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.ChangePasswordDTO;
import com.bigdata.modules.system.dto.UserDTO;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.service.SysUserService;
import com.bigdata.modules.system.vo.SysUserVO;
import com.bigdata.modules.system.vo.UserInfoVO;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/user")
public class UserController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private AesUtils aesUtils;

    @GetMapping("/list")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Page<SysUserVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status) {
        Page<SysUser> page = new Page<>(current, size);
        Page<SysUser> result = sysUserService.getUserList(page, username, status);
        Page<SysUserVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id == authentication.principal.userId")
    public Result<UserInfoVO> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getUserById(id);
        List<Long> roleIds = sysUserService.getUserRoleIds(id);
        UserInfoVO vo = new UserInfoVO(toVO(user), roleIds);
        return Result.success(vo);
    }

    @AuditLog(module = "用户管理", operation = "创建用户", targetType = "User")
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> save(@Validated(UserDTO.Create.class) @RequestBody UserDTO dto) {
        sysUserService.saveUser(dto);
        return Result.success();
    }

    @AuditLog(module = "用户管理", operation = "更新用户", targetType = "User")
    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> update(@Validated({UserDTO.Update.class, Default.class}) @RequestBody UserDTO dto) {
        sysUserService.updateUser(dto);
        return Result.success();
    }

    @AuditLog(module = "用户管理", operation = "删除用户", targetType = "User")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserService.deleteUser(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysUserService.changeStatus(id, status);
        return Result.success();
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        Long currentUserId = getCurrentUserId();
        try {
            dto.setOldPassword(aesUtils.decryptPassword(dto.getOldPassword()));
            dto.setNewPassword(aesUtils.decryptPassword(dto.getNewPassword()));
            dto.setConfirmPassword(aesUtils.decryptPassword(dto.getConfirmPassword()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("密码解密失败，请使用密钥加密后再传输");
        }
        sysUserService.changePassword(currentUserId, dto);
        return Result.success();
    }

    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> resetPassword(@PathVariable Long id) {
        sysUserService.resetPassword(id);
        return Result.success();
    }

    private Long getCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("未登录或登录已过期");
        }
        return userId;
    }

    private SysUserVO toVO(SysUser entity) {
        SysUserVO vo = new SysUserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        vo.setRealName(entity.getRealName());
        vo.setEmail(entity.getEmail());
        vo.setPhone(entity.getPhone());
        vo.setAvatar(entity.getAvatar());
        vo.setStatus(entity.getStatus());
        vo.setLastLoginTime(entity.getLastLoginTime());
        vo.setRoles(entity.getRoles());
        vo.setRoleIds(entity.getRoleIds());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
