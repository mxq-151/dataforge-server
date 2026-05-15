package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.dto.RoleDTO;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.entity.SysUserRole;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import com.bigdata.modules.system.mapper.SysUserRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SysRoleService {

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

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
            saveRoleUsers(role.getId(), dto.getUserIds());
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
            deleteRoleUsers(role.getId());
            if (!CollectionUtils.isEmpty(dto.getUserIds())) {
                saveRoleUsers(role.getId(), dto.getUserIds());
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
        deleteRoleUsers(id);
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
        List<Long> userIds = sysUserRoleMapper.selectUserIdsByRoleId(roleId);
        if (CollectionUtils.isEmpty(userIds)) {
            return List.of();
        }
        return sysUserMapper.selectBatchIds(userIds);
    }

    public List<Long> getUserIdsByRoleId(Long roleId) {
        return sysUserRoleMapper.selectUserIdsByRoleId(roleId);
    }

    private void saveRoleUsers(Long roleId, List<Long> userIds) {
        for (Long userId : userIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setCreateTime(LocalDateTime.now());
            sysUserRoleMapper.insert(userRole);
        }
    }

    private void deleteRoleUsers(Long roleId) {
        sysUserRoleMapper.deleteByRoleId(roleId);
    }
}
