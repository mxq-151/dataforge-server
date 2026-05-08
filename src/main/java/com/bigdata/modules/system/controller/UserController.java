package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.ChangePasswordDTO;
import com.bigdata.modules.system.dto.UserDTO;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.service.SysUserService;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/user")
public class UserController {

    @Autowired
    private SysUserService sysUserService;

    @GetMapping("/list")
    public Result<Page<SysUser>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status) {
        Page<SysUser> page = new Page<>(current, size);
        Page<SysUser> result = sysUserService.getUserList(page, username, status);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getUserById(id);
        List<Long> roleIds = sysUserService.getUserRoleIds(id);
        Map<String, Object> data = Map.of(
                "user", user,
                "roleIds", roleIds
        );
        return Result.success(data);
    }

    @PostMapping
    public Result<Void> save(@Validated(UserDTO.Create.class) @RequestBody UserDTO dto) {
        sysUserService.saveUser(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Validated({UserDTO.Update.class, Default.class}) @RequestBody UserDTO dto) {
        sysUserService.updateUser(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserService.deleteUser(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysUserService.changeStatus(id, status);
        return Result.success();
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        Long currentUserId = getCurrentUserId();
        sysUserService.changePassword(currentUserId, dto);
        return Result.success();
    }

    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        sysUserService.resetPassword(id);
        return Result.success();
    }

    @GetMapping("/current")
    public Result<Map<String, Object>> getCurrentUserInfo() {
        Long currentUserId = getCurrentUserId();
        SysUser user = sysUserService.getUserById(currentUserId);
        List<Long> roleIds = sysUserService.getUserRoleIds(currentUserId);
        Map<String, Object> data = Map.of(
                "user", user,
                "roleIds", roleIds
        );
        return Result.success(data);
    }

    private Long getCurrentUserId() {
        return 1L;
    }
}
