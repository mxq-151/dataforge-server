package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.dto.TenantDTO;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysTenantRole;
import com.bigdata.modules.system.entity.SysTenantUser;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysTenantMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class SysTenantService {

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    public Page<SysTenant> getTenantList(int current, int size, String tenantName, Integer status) {
        Page<SysTenant> page = new Page<>(current, size);
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        if (tenantName != null && !tenantName.isEmpty()) {
            wrapper.like(SysTenant::getTenantName, tenantName);
        }
        if (status != null) {
            wrapper.eq(SysTenant::getStatus, status);
        }
        wrapper.orderByDesc(SysTenant::getCreateTime);
        return sysTenantMapper.selectPage(page, wrapper);
    }

    public List<SysTenant> getAllTenants() {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysTenant::getStatus, 1);
        wrapper.orderByAsc(SysTenant::getId);
        return sysTenantMapper.selectList(wrapper);
    }

    public SysTenant getTenantById(Long id) {
        return sysTenantMapper.selectById(id);
    }

    public SysTenant getTenantByCode(String tenantCode) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysTenant::getTenantCode, tenantCode);
        return sysTenantMapper.selectOne(wrapper);
    }

    public SysTenant getTenantByUserId(Long userId) {
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
        if (tenantId == null) {
            return null;
        }
        return sysTenantMapper.selectById(tenantId);
    }

    public Long getTenantIdByUserId(Long userId) {
        return sysTenantUserMapper.selectTenantIdByUserId(userId);
    }

    public SysUser getUserByTenantId(Long tenantId) {
        List<Long> userIds = sysTenantUserMapper.selectUserIdsByTenantId(tenantId);
        if (CollectionUtils.isEmpty(userIds)) {
            return null;
        }
        return sysUserMapper.selectById(userIds.get(0));
    }

    @Transactional
    public void saveTenant(TenantDTO dto) {
        SysTenant existTenant = getTenantByCode(dto.getTenantCode());
        if (existTenant != null) {
            throw new BusinessException("租户编码已存在");
        }

        SysTenant tenant = new SysTenant();
        tenant.setTenantName(dto.getTenantName());
        tenant.setTenantCode(dto.getTenantCode());
        tenant.setContactName(dto.getContactName());
        tenant.setContactPhone(dto.getContactPhone());
        tenant.setContactEmail(dto.getContactEmail());
        tenant.setDescription(dto.getDescription());
        tenant.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        tenant.setPassword(dto.getPassword());

        sysTenantMapper.insert(tenant);

        if (dto.getUserId() != null) {
            bindUser(tenant.getId(), dto.getUserId());
        }
    }

    @Transactional
    public void updateTenant(TenantDTO dto) {
        SysTenant tenant = sysTenantMapper.selectById(dto.getId());
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        if (dto.getTenantCode() != null && !dto.getTenantCode().equals(tenant.getTenantCode())) {
            SysTenant existTenant = getTenantByCode(dto.getTenantCode());
            if (existTenant != null && !existTenant.getId().equals(dto.getId())) {
                throw new BusinessException("租户编码已存在");
            }
            tenant.setTenantCode(dto.getTenantCode());
        }

        if (dto.getTenantName() != null) {
            tenant.setTenantName(dto.getTenantName());
        }
        if (dto.getContactName() != null) {
            tenant.setContactName(dto.getContactName());
        }
        if (dto.getContactPhone() != null) {
            tenant.setContactPhone(dto.getContactPhone());
        }
        if (dto.getContactEmail() != null) {
            tenant.setContactEmail(dto.getContactEmail());
        }
        if (dto.getDescription() != null) {
            tenant.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            tenant.setStatus(dto.getStatus());
        }
        if (dto.getPassword() != null) {
            tenant.setPassword(dto.getPassword());
        }

        sysTenantMapper.updateById(tenant);

        if (dto.getUserId() != null) {
            unbindAllUsers(tenant.getId());
            bindUser(tenant.getId(), dto.getUserId());
        }
    }

    @Transactional
    public void deleteTenant(Long id) {
        SysTenant tenant = sysTenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        sysTenantRoleMapper.deleteByTenantId(id);
        unbindAllUsers(id);
        sysTenantMapper.deleteById(id);
    }

    public void changeStatus(Long id, Integer status) {
        SysTenant tenant = sysTenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        tenant.setStatus(status);
        sysTenantMapper.updateById(tenant);
    }

    @Transactional
    public void bindUser(Long tenantId, Long userId) {
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        SysTenantUser existBinding = sysTenantUserMapper.selectByUserId(userId);
        if (existBinding != null) {
            if (!existBinding.getTenantId().equals(tenantId)) {
                throw new BusinessException("该用户已绑定到其他租户，请先解绑");
            } else {
                throw new BusinessException("该用户已绑定到此租户");
            }
        }

        List<Long> existingUsers = sysTenantUserMapper.selectUserIdsByTenantId(tenantId);
        if (!CollectionUtils.isEmpty(existingUsers)) {
            throw new BusinessException("该租户已绑定用户，一个租户只能绑定一个用户");
        }

        SysTenantUser tenantUser = new SysTenantUser();
        tenantUser.setTenantId(tenantId);
        tenantUser.setUserId(userId);
        tenantUser.setCreateTime(LocalDateTime.now());
        sysTenantUserMapper.insert(tenantUser);
    }

    @Transactional
    public void unbindUser(Long tenantId, Long userId) {
        SysTenantUser tenantUser = sysTenantUserMapper.selectByUserId(userId);
        if (tenantUser == null || !tenantUser.getTenantId().equals(tenantId)) {
            throw new BusinessException("该用户未绑定到此租户");
        }
        sysTenantUserMapper.deleteByUserId(userId);
    }

    @Transactional
    public void unbindAllUsers(Long tenantId) {
        sysTenantUserMapper.deleteByTenantId(tenantId);
    }

    public List<Long> getRoleIdsByTenantId(Long tenantId) {
        return sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
    }

    public List<Long> getTenantIdsByRoleId(Long roleId) {
        return sysTenantRoleMapper.selectTenantIdsByRoleId(roleId);
    }

    @Transactional
    public void saveTenantRoles(Long tenantId, List<Long> roleIds) {
        LocalDateTime now = LocalDateTime.now();
        List<SysTenantRole> list = new ArrayList<>(roleIds.size());
        for (Long roleId : roleIds) {
            SysTenantRole tenantRole = new SysTenantRole();
            tenantRole.setTenantId(tenantId);
            tenantRole.setRoleId(roleId);
            tenantRole.setCreateTime(now);
            list.add(tenantRole);
        }
        sysTenantRoleMapper.batchInsertTenantRoles(list);
    }

    @Transactional
    public void deleteTenantRoles(Long tenantId) {
        sysTenantRoleMapper.deleteByTenantId(tenantId);
    }

    @Transactional
    public SysTenant ensureTenantForUser(Long userId) {
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
        if (tenantId != null) {
            return sysTenantMapper.selectById(tenantId);
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        String tenantCode = "T_" + user.getUsername();
        SysTenant existTenant = getTenantByCode(tenantCode);
        if (existTenant != null) {
            bindUser(existTenant.getId(), userId);
            return existTenant;
        }

        SysTenant tenant = new SysTenant();
        tenant.setTenantName(user.getRealName() != null ? user.getRealName() : user.getUsername());
        tenant.setTenantCode(tenantCode);
        tenant.setContactName(user.getRealName());
        tenant.setContactPhone(user.getPhone());
        tenant.setContactEmail(user.getEmail());
        tenant.setStatus(1);
        tenant.setOwnerId(userId);
        sysTenantMapper.insert(tenant);

        SysTenantUser tenantUser = new SysTenantUser();
        tenantUser.setTenantId(tenant.getId());
        tenantUser.setUserId(userId);
        tenantUser.setCreateTime(LocalDateTime.now());
        sysTenantUserMapper.insert(tenantUser);

        return tenant;
    }
}
