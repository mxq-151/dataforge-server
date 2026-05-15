package com.bigdata.modules.system.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysRoleProjectPermissionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SysRoleProjectPermissionService {

    public static final int PERMISSION_READ = 1;
    public static final int PERMISSION_EDIT = 2;
    public static final int PERMISSION_DELETE = 3;

    @Autowired
    private SysRoleProjectPermissionMapper sysRoleProjectPermissionMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    public List<SysRoleProjectPermission> getPermissionsByRoleId(Long roleId) {
        return sysRoleProjectPermissionMapper.selectByRoleId(roleId);
    }

    public List<SysRoleProjectPermission> getPermissionsByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleProjectPermissionMapper.selectByRoleIds(roleIds);
    }

    public List<SysRoleProjectPermission> getPermissionsByProjectId(Long projectId) {
        return sysRoleProjectPermissionMapper.selectByProjectId(projectId);
    }

    public SysRoleProjectPermission getPermission(Long roleId, Long projectId) {
        return sysRoleProjectPermissionMapper.selectByRoleIdAndProjectId(roleId, projectId);
    }

    public boolean hasPermission(Long roleId, Long projectId, int permissionType) {
        SysRoleProjectPermission permission = getPermission(roleId, projectId);
        if (permission == null) {
            return false;
        }
        return permission.getPermissionType() >= permissionType;
    }

    @Transactional
    public void assignPermission(Long roleId, Long projectId, Integer permissionType) {
        SysRole role = sysRoleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (role.getStatus() != 1) {
            throw new BusinessException("角色已被禁用，无法分配权限");
        }

        SysRoleProjectPermission existPermission = getPermission(roleId, projectId);
        if (existPermission != null) {
            existPermission.setPermissionType(permissionType);
            sysRoleProjectPermissionMapper.updateById(existPermission);
        } else {
            SysRoleProjectPermission permission = new SysRoleProjectPermission();
            permission.setRoleId(roleId);
            permission.setProjectId(projectId);
            permission.setPermissionType(permissionType);
            permission.setCreateTime(LocalDateTime.now());
            sysRoleProjectPermissionMapper.insert(permission);
        }
    }

    @Transactional
    public void batchAssignPermissions(Long roleId, List<ProjectPermissionItem> items) {
        SysRole role = sysRoleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (role.getStatus() != 1) {
            throw new BusinessException("角色已被禁用，无法分配权限");
        }

        for (ProjectPermissionItem item : items) {
            SysRoleProjectPermission existPermission = getPermission(roleId, item.getProjectId());
            if (existPermission != null) {
                existPermission.setPermissionType(item.getPermissionType());
                sysRoleProjectPermissionMapper.updateById(existPermission);
            } else {
                SysRoleProjectPermission permission = new SysRoleProjectPermission();
                permission.setRoleId(roleId);
                permission.setProjectId(item.getProjectId());
                permission.setPermissionType(item.getPermissionType());
                permission.setCreateTime(LocalDateTime.now());
                sysRoleProjectPermissionMapper.insert(permission);
            }
        }
    }

    @Transactional
    public void removePermission(Long roleId, Long projectId) {
        sysRoleProjectPermissionMapper.deleteByRoleIdAndProjectId(roleId, projectId);
    }

    @Transactional
    public void removePermissionsByRoleId(Long roleId) {
        sysRoleProjectPermissionMapper.deleteByRoleId(roleId);
    }

    @Transactional
    public void removePermissionsByProjectId(Long projectId) {
        sysRoleProjectPermissionMapper.deleteByProjectId(projectId);
    }

    public static class ProjectPermissionItem {
        private Long projectId;
        private Integer permissionType;

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public Integer getPermissionType() {
            return permissionType;
        }

        public void setPermissionType(Integer permissionType) {
            this.permissionType = permissionType;
        }
    }
}
