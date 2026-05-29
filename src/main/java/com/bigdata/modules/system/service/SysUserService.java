package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.JwtTokenUtils;
import com.bigdata.common.utils.PasswordUtils;
import com.bigdata.modules.system.dto.ChangePasswordDTO;
import com.bigdata.modules.system.dto.UserDTO;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysTenantRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SysUserService implements UserDetailsService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysTenantService sysTenantService;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    public String login(String username, String password) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        if (!PasswordUtils.verify(password, user.getPassword())) {
            throw new BusinessException("密码错误");
        }

        user.setLastLoginTime(LocalDateTime.now());
        sysUserMapper.updateById(user);

        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(user.getId());
        List<Long> roleIds = Collections.emptyList();
        if (tenantId != null) {
            roleIds = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
        }

        String role = "ROLE_USER";
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            if (!CollectionUtils.isEmpty(roles)) {
                role = roles.stream()
                        .map(r -> "ROLE_" + r.getRoleCode().toUpperCase())
                        .collect(Collectors.joining(","));
            }
        }

        return jwtTokenUtils.generateToken(user.getId(), user.getUsername(), role, tenantId);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return new User(
                user.getUsername(),
                user.getPassword(),
                user.getStatus() == 1,
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    public Page<SysUser> getUserList(Page<SysUser> page, String username, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isEmpty()) {
            wrapper.like(SysUser::getUsername, username);
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByDesc(SysUser::getCreateTime);
        Page<SysUser> userPage = sysUserMapper.selectPage(page, wrapper);

        List<SysUser> users = userPage.getRecords();
        if (!CollectionUtils.isEmpty(users)) {
            List<Long> userIds = users.stream().map(SysUser::getId).collect(Collectors.toList());

            Map<Long, Long> userTenantIdMap = new HashMap<>();
            for (Long userId : userIds) {
                Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
                if (tenantId != null) {
                    userTenantIdMap.put(userId, tenantId);
                }
            }

            Set<Long> allTenantIds = new HashSet<>(userTenantIdMap.values());
            Map<Long, List<Long>> tenantRoleIdsMap = new HashMap<>();
            if (!allTenantIds.isEmpty()) {
                List<SysTenantRole> tenantRoles = sysTenantRoleMapper.selectByTenantIds(new ArrayList<>(allTenantIds));
                tenantRoleIdsMap = tenantRoles.stream()
                        .collect(Collectors.groupingBy(
                                SysTenantRole::getTenantId,
                                Collectors.mapping(SysTenantRole::getRoleId, Collectors.toList())
                        ));
            }

            Set<Long> allRoleIds = new HashSet<>();
            for (List<Long> ids : tenantRoleIdsMap.values()) {
                allRoleIds.addAll(ids);
            }

            Map<Long, SysRole> roleMap;
            if (!allRoleIds.isEmpty()) {
                List<SysRole> allRoles = sysRoleMapper.selectBatchIds(allRoleIds);
                roleMap = allRoles.stream()
                        .collect(Collectors.toMap(SysRole::getId, r -> r));
            } else {
                roleMap = new HashMap<>();
            }

            for (SysUser user : users) {
                Long tenantId = userTenantIdMap.get(user.getId());
                List<Long> roleIds = tenantId != null
                        ? tenantRoleIdsMap.getOrDefault(tenantId, List.of())
                        : List.of();
                user.setRoleIds(roleIds);

                List<String> roleNames = roleIds.stream()
                        .map(roleId -> {
                            SysRole role = roleMap.get(roleId);
                            return role != null ? role.getRoleName() : "";
                        })
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toList());
                user.setRoles(roleNames);
            }
        }

        return userPage;
    }

    public SysUser getUserById(Long id) {
        return sysUserMapper.selectById(id);
    }

    public SysUser getUserByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        return sysUserMapper.selectOne(wrapper);
    }

    @Transactional
    public void saveUser(UserDTO dto) {
        SysUser existUser = sysUserMapper.selectByUsername(dto.getUsername());
        if (existUser != null) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(PasswordUtils.encrypt(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setAvatar(dto.getAvatar());
        user.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

        sysUserMapper.insert(user);

        if (!CollectionUtils.isEmpty(dto.getRoleIds())) {
            SysTenant tenant = sysTenantService.ensureTenantForUser(user.getId());
            sysTenantRoleMapper.deleteByTenantId(tenant.getId());
            sysTenantService.saveTenantRoles(tenant.getId(), dto.getRoleIds());
        }
    }

    @Transactional
    public void updateUser(UserDTO dto) {
        SysUser user = sysUserMapper.selectById(dto.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (dto.getUsername() != null && !dto.getUsername().equals(user.getUsername())) {
            SysUser existUser = sysUserMapper.selectByUsername(dto.getUsername());
            if (existUser != null && !existUser.getId().equals(dto.getId())) {
                throw new BusinessException("用户名已存在");
            }
            user.setUsername(dto.getUsername());
        }

        if (dto.getRealName() != null) {
            user.setRealName(dto.getRealName());
        }
        if (dto.getEmail() != null) {
            user.setEmail(dto.getEmail());
        }
        if (dto.getPhone() != null) {
            user.setPhone(dto.getPhone());
        }
        if (dto.getAvatar() != null) {
            user.setAvatar(dto.getAvatar());
        }
        if (dto.getStatus() != null) {
            user.setStatus(dto.getStatus());
        }
        if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            user.setPassword(PasswordUtils.encrypt(dto.getPassword()));
        }

        sysUserMapper.updateById(user);

        if (dto.getRoleIds() != null) {
            SysTenant tenant = sysTenantService.ensureTenantForUser(user.getId());
            sysTenantRoleMapper.deleteByTenantId(tenant.getId());
            if (!CollectionUtils.isEmpty(dto.getRoleIds())) {
                sysTenantService.saveTenantRoles(tenant.getId(), dto.getRoleIds());
            }
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if ("admin".equals(user.getUsername())) {
            throw new BusinessException("不能删除管理员账号");
        }
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(id);
        if (tenantId != null) {
            sysTenantService.unbindUser(tenantId, id);
        }
        sysUserMapper.deleteById(id);
    }

    public void changeStatus(Long id, Integer status) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if ("admin".equals(user.getUsername()) && status == 0) {
            throw new BusinessException("不能禁用管理员账号");
        }
        user.setStatus(status);
        sysUserMapper.updateById(user);
    }

    public void changePassword(Long userId, ChangePasswordDTO dto) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!PasswordUtils.verify(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的密码不一致");
        }
        user.setPassword(PasswordUtils.encrypt(dto.getNewPassword()));
        sysUserMapper.updateById(user);
    }

    public void resetPassword(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setPassword(PasswordUtils.encrypt("123456"));
        sysUserMapper.updateById(user);
    }

    public List<Long> getUserRoleIds(Long userId) {
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
        if (tenantId == null) {
            return Collections.emptyList();
        }
        return sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
    }
}
