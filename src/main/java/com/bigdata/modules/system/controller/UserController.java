package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.dto.ChangePasswordDTO;
import com.bigdata.modules.system.dto.UserDTO;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysMenuPermissionMapper;
import com.bigdata.modules.system.mapper.SysUserRoleMapper;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.service.SysUserService;
import com.bigdata.modules.system.vo.SysUserVO;
import com.bigdata.modules.system.vo.UserInfoVO;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/user")
public class UserController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysRoleProjectPermissionService sysRoleProjectPermissionService;

    @GetMapping("/list")
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
    public Result<UserInfoVO> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getUserById(id);
        List<Long> roleIds = sysUserService.getUserRoleIds(id);
        UserInfoVO vo = new UserInfoVO(toVO(user), roleIds);
        return Result.success(vo);
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
    public Result<UserInfoVO> getCurrentUserInfo() {
        Long currentUserId = getCurrentUserId();
        SysUser user = sysUserService.getUserById(currentUserId);
        List<Long> roleIds = sysUserService.getUserRoleIds(currentUserId);
        SysUserVO userVO = toVO(user);

        if (!CollectionUtils.isEmpty(roleIds)) {
            List<Long> menuPermissionIds = new ArrayList<>();
            for (Long roleId : roleIds) {
                List<Long> ids = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(roleId);
                if (ids != null) {
                    menuPermissionIds.addAll(ids);
                }
            }
            Set<Long> uniqueMenuPermIds = menuPermissionIds.stream().collect(Collectors.toSet());

            if (!uniqueMenuPermIds.isEmpty()) {
                List<SysMenuPermission> menuPermissions = sysMenuPermissionMapper.selectBatchIds(uniqueMenuPermIds);
                userVO.setMenuTags(menuPermissions.stream()
                        .map(SysMenuPermission::getMenuCode)
                        .filter(code -> code != null && !code.isEmpty())
                        .distinct()
                        .collect(Collectors.toList()));
            } else {
                userVO.setMenuTags(Collections.emptyList());
            }

            List<SysRoleProjectPermission> projectPermissions = roleIds.stream()
                    .flatMap(roleId -> sysRoleProjectPermissionService.getPermissionsByRoleId(roleId).stream())
                    .collect(Collectors.toList());

            if (!CollectionUtils.isEmpty(projectPermissions)) {
                List<Long> projectIds = projectPermissions.stream()
                        .map(SysRoleProjectPermission::getProjectId)
                        .distinct()
                        .collect(Collectors.toList());
                List<DevProject> projects = devProjectMapper.selectBatchIds(projectIds);
                userVO.setProjectCodes(projects.stream()
                        .map(DevProject::getProjectCode)
                        .collect(Collectors.toList()));
            } else {
                userVO.setProjectCodes(Collections.emptyList());
            }
        } else {
            userVO.setMenuTags(Collections.emptyList());
            userVO.setProjectCodes(Collections.emptyList());
        }

        UserInfoVO vo = new UserInfoVO(userVO, roleIds);
        return Result.success(vo);
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
