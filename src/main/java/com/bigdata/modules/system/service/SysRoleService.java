package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.dto.RoleDTO;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysTenantUser;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class SysRoleService {

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantService sysTenantService;

    public Page<SysRole> getRoleList(int current, int size, String roleName) {
        Page<SysRole> page = new Page<>(current, size);
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (roleName != null && !roleName.isEmpty()) {
            wrapper.like(SysRole::getRoleName, roleName);
        }
        wrapper.orderByAsc(SysRole::getId);
        return sysRoleMapper.selectPage(page, wrapper);
    }

    public List<SysRole> getAllRoles() {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getStatus, 1);
        wrapper.orderByAsc(SysRole::getId);
        return sysRoleMapper.selectList(wrapper);
    }

    public SysRole getRoleById(Long id) {
        return sysRoleMapper.selectById(id);
    }

    public SysRole getRoleByCode(String roleCode) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getRoleCode, roleCode);
        return sysRoleMapper.selectOne(wrapper);
    }

    @Transactional
    public void saveRole(RoleDTO dto) {
        SysRole existRole = getRoleByCode(dto.getRoleCode());
        if (existRole != null) {
            throw new BusinessException("角色编码已存在");
        }

        SysRole role = new SysRole();
        role.setRoleName(dto.getRoleName());
        role.setRoleCode(dto.getRoleCode());
        role.setRoleType(dto.getRoleType());
        role.setDescription(dto.getDescription());
        role.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

        sysRoleMapper.insert(role);

        if (!CollectionUtils.isEmpty(dto.getUserIds())) {
            saveRoleTenantsFromUserIds(role.getId(), dto.getUserIds());
        }
    }

    @Transactional
    public void updateRole(RoleDTO dto) {
        SysRole role = sysRoleMapper.selectById(dto.getId());
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        if (dto.getRoleCode() != null && !dto.getRoleCode().equals(role.getRoleCode())) {
            SysRole existRole = getRoleByCode(dto.getRoleCode());
            if (existRole != null && !existRole.getId().equals(dto.getId())) {
                throw new BusinessException("角色编码已存在");
            }
            role.setRoleCode(dto.getRoleCode());
        }

        if (dto.getRoleName() != null) {
            role.setRoleName(dto.getRoleName());
        }
        if (dto.getRoleType() != null) {
            role.setRoleType(dto.getRoleType());
        }
        if (dto.getDescription() != null) {
            role.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            role.setStatus(dto.getStatus());
        }

        sysRoleMapper.updateById(role);

        if (dto.getUserIds() != null) {
            sysTenantRoleMapper.deleteByRoleId(role.getId());
            if (!CollectionUtils.isEmpty(dto.getUserIds())) {
                saveRoleTenantsFromUserIds(role.getId(), dto.getUserIds());
            }
        }
    }

    @Transactional
    public void deleteRole(Long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if ("admin".equals(role.getRoleCode())) {
            throw new BusinessException("不能删除管理员角色");
        }
        sysTenantRoleMapper.deleteByRoleId(id);
        sysRoleMapper.deleteById(id);
    }

    public void changeStatus(Long id, Integer status) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if ("admin".equals(role.getRoleCode()) && status == 0) {
            throw new BusinessException("不能禁用管理员角色");
        }
        role.setStatus(status);
        sysRoleMapper.updateById(role);
    }

    public List<SysUser> getUsersByRoleId(Long roleId) {
        List<Long> tenantIds = sysTenantRoleMapper.selectTenantIdsByRoleId(roleId);
        if (CollectionUtils.isEmpty(tenantIds)) {
            return List.of();
        }
        List<Long> userIds = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            List<Long> usersInTenant = sysTenantUserMapper.selectUserIdsByTenantId(tenantId);
            userIds.addAll(usersInTenant);
        }
        if (userIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectBatchIds(userIds);
    }

    public List<Long> getUserIdsByRoleId(Long roleId) {
        List<Long> tenantIds = sysTenantRoleMapper.selectTenantIdsByRoleId(roleId);
        if (CollectionUtils.isEmpty(tenantIds)) {
            return List.of();
        }
        List<Long> userIds = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            List<Long> usersInTenant = sysTenantUserMapper.selectUserIdsByTenantId(tenantId);
            userIds.addAll(usersInTenant);
        }
        return userIds;
    }

    private void saveRoleTenantsFromUserIds(Long roleId, List<Long> userIds) {
        for (Long userId : userIds) {
            SysTenantUser tenantUser = sysTenantUserMapper.selectByUserId(userId);
            if (tenantUser == null) {
                continue;
            }
            Long tenantId = tenantUser.getTenantId();
            List<Long> existingRoleIds = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
            if (!existingRoleIds.contains(roleId)) {
                com.bigdata.modules.system.entity.SysTenantRole tenantRole =
                        new com.bigdata.modules.system.entity.SysTenantRole();
                tenantRole.setTenantId(tenantId);
                tenantRole.setRoleId(roleId);
                tenantRole.setCreateTime(java.time.LocalDateTime.now());
                sysTenantRoleMapper.insert(tenantRole);
            }
        }
    }
}
