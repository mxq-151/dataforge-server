package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.AesUtils;
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
import com.bigdata.modules.system.mapper.SysRoleMenuPermissionMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.service.SysUserService;
import com.bigdata.modules.system.vo.CurrentUserInfoVO;
import com.bigdata.modules.system.vo.LoginVO;
import com.bigdata.modules.system.vo.ProjectPermissionInfoVO;
import com.bigdata.modules.system.vo.SysRoleVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private SysRoleMenuPermissionMapper sysRoleMenuPermissionMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @Autowired
    private AesUtils aesUtils;

    @AuditLog(module = "认证", operation = "用户登录", targetType = "User")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        String password;
        try {
            password = aesUtils.decryptPassword(loginDTO.getPassword());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("密码解密失败，请使用密钥加密后再传输");
        }

        String token = sysUserService.login(loginDTO.getUsername(), password);

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
            roleSet.add("ROLE_SUPER_ADMIN");
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

        List<SysRoleVO> roleVOList = new ArrayList<>();
        if (Integer.valueOf(1).equals(user.getIsAdmin())) {
            SysRoleVO adminRole = new SysRoleVO();
            adminRole.setRoleName("超级管理员");
            adminRole.setRoleCode("SUPER_ADMIN");
            adminRole.setRoleType("SUPER_ADMIN");
            roleVOList.add(adminRole);
        }
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            roles.forEach(r -> {
                SysRoleVO roleVO = new SysRoleVO();
                roleVO.setId(r.getId());
                roleVO.setRoleName(r.getRoleName());
                roleVO.setRoleCode(r.getRoleCode());
                roleVO.setRoleType(r.getRoleType());
                roleVO.setDescription(r.getDescription());
                roleVO.setStatus(r.getStatus());
                roleVOList.add(roleVO);
            });
        }
        if (roleVOList.isEmpty()) {
            SysRoleVO userRole = new SysRoleVO();
            userRole.setRoleName("普通用户");
            userRole.setRoleCode("USER");
            roleVOList.add(userRole);
        }
        vo.setRoles(roleVOList);

        SysTenant tenant = tenantId != null ? sysTenantService.getTenantById(tenantId) : null;
        if (tenant != null) {
            vo.setTenantId(tenant.getId());
            vo.setTenantName(tenant.getTenantName());
        }

        List<Long> menuPermissionIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(roleIds)) {
            menuPermissionIds = sysRoleMenuPermissionMapper.selectMenuPermissionIdsByRoleIds(roleIds);
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
            // 同一用户可能通过多个角色拥有同一项目不同权限,取最高权限类型
            Map<Long, Integer> projectMaxType = new HashMap<>();
            for (SysRoleProjectPermission perm : projectPermissions) {
                projectMaxType.merge(perm.getProjectId(), perm.getPermissionType(),
                        (a, b) -> a >= b ? a : b);
            }

            List<DevProject> projects = devProjectMapper.selectBatchIds(new ArrayList<>(projectMaxType.keySet()));
            Map<Long, DevProject> projectById = projects.stream()
                    .collect(Collectors.toMap(DevProject::getId, p -> p, (p1, p2) -> p1));

            List<ProjectPermissionInfoVO> projectInfoList = new ArrayList<>();
            projectMaxType.forEach((projectId, permissionType) -> {
                DevProject project = projectById.get(projectId);
                if (project == null) {
                    return;
                }
                ProjectPermissionInfoVO info = new ProjectPermissionInfoVO();
                info.setProjectCode(project.getProjectCode());
                info.setPermissionType(permissionType);
                info.setPermissionTypeName(ProjectPermissionInfoVO.permissionTypeName(permissionType));
                projectInfoList.add(info);
            });
            vo.setProjectCodes(projectInfoList);
        } else {
            vo.setProjectCodes(Collections.emptyList());
        }

        return Result.success(vo);
    }
}
