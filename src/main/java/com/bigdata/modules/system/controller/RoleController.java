package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.RoleDTO;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.service.SysRoleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/role")
public class RoleController {

    @Autowired
    private SysRoleService sysRoleService;

    @GetMapping("/list")
    public Result<Page<SysRole>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String roleName) {
        Page<SysRole> result = sysRoleService.getRoleList(current, size, roleName);
        return Result.success(result);
    }

    @GetMapping("/all")
    public Result<List<SysRole>> getAllRoles() {
        List<SysRole> roles = sysRoleService.getAllRoles();
        return Result.success(roles);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        SysRole role = sysRoleService.getRoleById(id);
        List<Long> userIds = sysRoleService.getUserIdsByRoleId(id);
        Map<String, Object> data = Map.of(
                "role", role,
                "userIds", userIds
        );
        return Result.success(data);
    }

    @GetMapping("/{id}/users")
    public Result<List<SysUser>> getUsersByRoleId(@PathVariable Long id) {
        List<SysUser> users = sysRoleService.getUsersByRoleId(id);
        return Result.success(users);
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody RoleDTO dto) {
        sysRoleService.saveRole(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody RoleDTO dto) {
        sysRoleService.updateRole(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysRoleService.deleteRole(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysRoleService.changeStatus(id, status);
        return Result.success();
    }
}
