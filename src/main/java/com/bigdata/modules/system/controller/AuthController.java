package com.bigdata.modules.system.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.JwtTokenUtils;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.dto.ChangePasswordDTO;
import com.bigdata.modules.system.dto.LoginDTO;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysMenuPermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysUserRoleMapper;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.service.SysUserService;
import com.bigdata.modules.system.vo.CurrentUserInfoVO;
import com.bigdata.modules.system.vo.LoginVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/auth")
public class AuthController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysTenantService sysTenantService;

    @Autowired
    private SysRoleProjectPermissionService sysRoleProjectPermissionService;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        String token = sysUserService.login(loginDTO.getUsername(), loginDTO.getPassword());

        SysUser user = sysUserService.getUserByUsername(loginDTO.getUsername());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        LoginVO vo = new LoginVO(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setAvatar(user.getAvatar());

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            vo.setRoles(roles.stream()
                    .map(r -> "ROLE_" + r.getRoleCode().toUpperCase())
                    .collect(Collectors.toList()));
        } else {
            vo.setRoles(Collections.singletonList("ROLE_USER"));
        }

        SysTenant tenant = sysTenantService.getTenantByUserId(user.getId());
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        return Result.success(vo);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        SecurityContextHolderClear();
        return Result.success();
    }

    @GetMapping("/info")
    public Result<CurrentUserInfoVO> getUserInfo() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("未登录或登录已过期");
        }

        SysUser user = sysUserService.getUserById(currentUserId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        CurrentUserInfoVO vo = new CurrentUserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        vo.setRoleIds(roleIds);
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            vo.setRoles(roles.stream().map(SysRole::getRoleName).collect(Collectors.toList()));
        } else {
            vo.setRoles(Collections.singletonList("普通用户"));
        }

        SysTenant tenant = sysTenantService.getTenantByUserId(user.getId());
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        List<Long> menuPermissionIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(roleIds)) {
            for (Long roleId : roleIds) {
                List<Long> ids = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(roleId);
                if (ids != null) {
                    menuPermissionIds.addAll(ids);
                }
            }
        }
        Set<Long> uniqueMenuPermIds = menuPermissionIds.stream().collect(Collectors.toSet());

        if (!uniqueMenuPermIds.isEmpty()) {
            List<SysMenuPermission> menuPermissions = sysMenuPermissionMapper.selectBatchIds(uniqueMenuPermIds);
            vo.setMenuTags(menuPermissions.stream()
                    .map(SysMenuPermission::getMenuCode)
                    .filter(code -> code != null && !code.isEmpty())
                    .distinct()
                    .collect(Collectors.toList()));
        } else {
            vo.setMenuTags(Collections.emptyList());
        }

        List<SysRoleProjectPermission> projectPermissions = roleIds != null && !roleIds.isEmpty()
                ? roleIds.stream()
                    .flatMap(roleId -> sysRoleProjectPermissionService.getPermissionsByRoleId(roleId).stream())
                    .collect(Collectors.toList())
                : Collections.emptyList();

        if (!CollectionUtils.isEmpty(projectPermissions)) {
            List<Long> projectIds = projectPermissions.stream()
                    .map(SysRoleProjectPermission::getProjectId)
                    .distinct()
                    .collect(Collectors.toList());
            List<DevProject> projects = devProjectMapper.selectBatchIds(projectIds);
            vo.setProjectCodes(projects.stream()
                    .map(DevProject::getProjectCode)
                    .collect(Collectors.toList()));
        } else {
            vo.setProjectCodes(Collections.emptyList());
        }

        return Result.success(vo);
    }

    @PostMapping("/refresh")
    public Result<LoginVO> refreshToken() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        String currentUsername = SecurityUtils.getCurrentUsername();
        if (currentUserId == null || currentUsername == null) {
            throw new BusinessException("未登录或登录已过期");
        }

        SysUser user = sysUserService.getUserById(currentUserId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        String role = "ROLE_USER";
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            if (!CollectionUtils.isEmpty(roles)) {
                role = roles.stream()
                        .map(r -> "ROLE_" + r.getRoleCode().toUpperCase())
                        .collect(Collectors.joining(","));
            }
        }

        String newToken = jwtTokenUtils.generateToken(user.getId(), user.getUsername(), role);

        LoginVO vo = new LoginVO(newToken);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setAvatar(user.getAvatar());
        vo.setRoles(List.of(role.split(",")));

        SysTenant tenant = sysTenantService.getTenantByUserId(user.getId());
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        return Result.success(vo);
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("未登录或登录已过期");
        }
        sysUserService.changePassword(currentUserId, dto);
        return Result.success();
    }

    private void SecurityContextHolderClear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
