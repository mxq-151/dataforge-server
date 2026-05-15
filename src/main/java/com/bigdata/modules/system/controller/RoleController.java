package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.RoleDTO;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.service.SysRoleService;
import com.bigdata.modules.system.vo.RoleDetailVO;
import com.bigdata.modules.system.vo.SysRoleVO;
import com.bigdata.modules.system.vo.SysUserVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/role")
public class RoleController {

    @Autowired
    private SysRoleService sysRoleService;

    @GetMapping("/list")
    public Result<Page<SysRoleVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String roleName) {
        Page<SysRole> page = sysRoleService.getRoleList(current, size, roleName);
        Page<SysRoleVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toRoleVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/all")
    public Result<List<SysRoleVO>> getAllRoles() {
        List<SysRole> roles = sysRoleService.getAllRoles();
        List<SysRoleVO> voList = roles.stream().map(this::toRoleVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/{id}")
    public Result<RoleDetailVO> getById(@PathVariable Long id) {
        SysRole role = sysRoleService.getRoleById(id);
        List<Long> userIds = sysRoleService.getUserIdsByRoleId(id);
        RoleDetailVO vo = new RoleDetailVO(toRoleVO(role), userIds);
        return Result.success(vo);
    }

    @GetMapping("/{id}/users")
    public Result<List<SysUserVO>> getUsersByRoleId(@PathVariable Long id) {
        List<SysUser> users = sysRoleService.getUsersByRoleId(id);
        List<SysUserVO> voList = users.stream().map(this::toUserVO).collect(Collectors.toList());
        return Result.success(voList);
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

    private SysRoleVO toRoleVO(SysRole entity) {
        SysRoleVO vo = new SysRoleVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private SysUserVO toUserVO(SysUser entity) {
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
