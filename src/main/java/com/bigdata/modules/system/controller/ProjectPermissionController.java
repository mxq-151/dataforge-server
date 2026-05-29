package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.BatchProjectPermissionDTO;
import com.bigdata.modules.system.dto.ProjectPermissionDTO;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.vo.ProjectPermissionVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/project-permission")
public class ProjectPermissionController {

    @Autowired
    private SysRoleProjectPermissionService sysRoleProjectPermissionService;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @GetMapping("/role/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<ProjectPermissionVO>> getPermissionsByRoleId(@PathVariable Long roleId) {
        List<SysRoleProjectPermission> permissions = sysRoleProjectPermissionService.getPermissionsByRoleId(roleId);
        List<ProjectPermissionVO> voList = toVOList(permissions);
        return Result.success(voList);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<ProjectPermissionVO>> getPermissionsByProjectId(@PathVariable Long projectId) {
        List<SysRoleProjectPermission> permissions = sysRoleProjectPermissionService.getPermissionsByProjectId(projectId);
        List<ProjectPermissionVO> voList = toVOList(permissions);
        return Result.success(voList);
    }

    @GetMapping("/check")
    public Result<Boolean> checkPermission(
            @RequestParam Long roleId,
            @RequestParam Long projectId,
            @RequestParam Integer permissionType) {
        boolean hasPermission = sysRoleProjectPermissionService.hasPermission(roleId, projectId, permissionType);
        return Result.success(hasPermission);
    }

    @AuditLog(module = "权限管理", operation = "分配项目权限", targetType = "ProjectPermission")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> assignPermission(@Valid @RequestBody ProjectPermissionDTO dto) {
        sysRoleProjectPermissionService.assignPermission(dto.getRoleId(), dto.getProjectId(), dto.getPermissionType());
        return Result.success();
    }

    @AuditLog(module = "权限管理", operation = "批量分配项目权限", targetType = "ProjectPermission")
    @PostMapping("/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> batchAssignPermissions(@Valid @RequestBody BatchProjectPermissionDTO dto) {
        List<SysRoleProjectPermissionService.ProjectPermissionItem> items = dto.getItems().stream()
                .map(item -> {
                    SysRoleProjectPermissionService.ProjectPermissionItem permissionItem = new SysRoleProjectPermissionService.ProjectPermissionItem();
                    permissionItem.setProjectId(item.getProjectId());
                    permissionItem.setPermissionType(item.getPermissionType());
                    return permissionItem;
                })
                .collect(Collectors.toList());
        sysRoleProjectPermissionService.batchAssignPermissions(dto.getRoleId(), items);
        return Result.success();
    }

    @AuditLog(module = "权限管理", operation = "移除项目权限", targetType = "ProjectPermission")
    @DeleteMapping("/{roleId}/{projectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> removePermission(@PathVariable Long roleId, @PathVariable Long projectId) {
        sysRoleProjectPermissionService.removePermission(roleId, projectId);
        return Result.success();
    }

    @AuditLog(module = "权限管理", operation = "按角色移除项目权限", targetType = "ProjectPermission")
    @DeleteMapping("/role/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> removePermissionsByRoleId(@PathVariable Long roleId) {
        sysRoleProjectPermissionService.removePermissionsByRoleId(roleId);
        return Result.success();
    }

    @AuditLog(module = "权限管理", operation = "按项目移除项目权限", targetType = "ProjectPermission")
    @DeleteMapping("/project/{projectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> removePermissionsByProjectId(@PathVariable Long projectId) {
        sysRoleProjectPermissionService.removePermissionsByProjectId(projectId);
        return Result.success();
    }

    private List<ProjectPermissionVO> toVOList(List<SysRoleProjectPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }

        Set<Long> roleIds = permissions.stream()
                .map(SysRoleProjectPermission::getRoleId)
                .collect(Collectors.toSet());
        Set<Long> projectIds = permissions.stream()
                .map(SysRoleProjectPermission::getProjectId)
                .collect(Collectors.toSet());

        Map<Long, SysRole> roleMap;
        if (!roleIds.isEmpty()) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            roleMap = roles.stream().collect(Collectors.toMap(SysRole::getId, r -> r));
        } else {
            roleMap = Map.of();
        }

        Map<Long, DevProject> projectMap;
        if (!projectIds.isEmpty()) {
            List<DevProject> projects = devProjectMapper.selectBatchIds(projectIds);
            projectMap = projects.stream().collect(Collectors.toMap(DevProject::getId, p -> p));
        } else {
            projectMap = Map.of();
        }

        return permissions.stream().map(entity -> {
            ProjectPermissionVO vo = new ProjectPermissionVO();
            BeanUtils.copyProperties(entity, vo);

            SysRole role = roleMap.get(entity.getRoleId());
            if (role != null) {
                vo.setRoleName(role.getRoleName());
            }

            DevProject project = projectMap.get(entity.getProjectId());
            if (project != null) {
                vo.setProjectName(project.getProjectName());
            }

            switch (entity.getPermissionType()) {
                case 1:
                    vo.setPermissionTypeName("可读");
                    break;
                case 2:
                    vo.setPermissionTypeName("可编辑");
                    break;
                case 3:
                    vo.setPermissionTypeName("可删除");
                    break;
                default:
                    vo.setPermissionTypeName("未知");
                    break;
            }

            return vo;
        }).collect(Collectors.toList());
    }
}
