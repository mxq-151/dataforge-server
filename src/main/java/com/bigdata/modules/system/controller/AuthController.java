package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.dto.LoginDTO;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysMenuPermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
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
import java.util.LinkedHashSet;
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
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @AuditLog(module = "认证", operation = "用户登录", targetType = "User")
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

        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(user.getId());
        List<Long> roleIds = Collections.emptyList();
        if (tenantId != null) {
            roleIds = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
        }

        Set<String> roleSet = new LinkedHashSet<>();
        if (Integer.valueOf(1).equals(user.getIsAdmin())) {
            roleSet.add("ROLE_ADMIN");
        }
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            roles.forEach(r -> roleSet.add("ROLE_" + r.getRoleCode().toUpperCase()));
        }
        if (roleSet.isEmpty()) {
            roleSet.add("ROLE_USER");
        }
        vo.setRoles(new ArrayList<>(roleSet));

        SysTenant tenant = tenantId != null ? sysTenantService.getTenantById(tenantId) : null;
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        return Result.success(vo);
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

        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(user.getId());
        List<Long> roleIds = Collections.emptyList();
        if (tenantId != null) {
            roleIds = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
        }

        vo.setRoleIds(roleIds);

        Set<String> roleNameSet = new LinkedHashSet<>();
        if (Integer.valueOf(1).equals(user.getIsAdmin())) {
            roleNameSet.add("超级管理员");
        }
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            roles.forEach(r -> roleNameSet.add(r.getRoleName()));
        }
        if (roleNameSet.isEmpty()) {
            roleNameSet.add("普通用户");
        }
        vo.setRoles(new ArrayList<>(roleNameSet));

        SysTenant tenant = tenantId != null ? sysTenantService.getTenantById(tenantId) : null;
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        List<Long> menuPermissionIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(roleIds)) {
            menuPermissionIds = sysUserRoleMapper.selectMenuPermissionIdsByRoleIds(roleIds);
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

        List<SysRoleProjectPermission> projectPermissions = !CollectionUtils.isEmpty(roleIds)
                ? sysRoleProjectPermissionService.getPermissionsByRoleIds(roleIds)
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
}
