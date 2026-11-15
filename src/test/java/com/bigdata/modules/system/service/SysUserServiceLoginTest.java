package com.bigdata.modules.system.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.JwtTokenUtils;
import com.bigdata.common.utils.PasswordUtils;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录规则：不存在 / 禁用 / 密码错误三条拒绝路径，以及成功登录后 JWT 声明的内容。
 *
 * <p>登录是所有权限链路的入口，此前 SysUserService 的行覆盖只有 2.9%，
 * 三条拒绝分支从未被执行过。</p>
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("System模块 - 登录规则与令牌声明")
class SysUserServiceLoginTest {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Test
    @DisplayName("用户不存在：抛出业务异常且提示可读")
    void rejectsUnknownUser() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.login("nobody_" + UUID.randomUUID(), "any"));
        assertEquals("用户不存在", ex.getMessage());
    }

    @Test
    @DisplayName("账号被禁用：即使密码正确也拒绝")
    void rejectsDisabledUser() {
        SysUser user = createUser(0, 0, "pw123456");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.login(user.getUsername(), "pw123456"));
        assertEquals("账号已被禁用", ex.getMessage());
    }

    @Test
    @DisplayName("密码错误：拒绝，且不签发 token")
    void rejectsWrongPassword() {
        SysUser user = createUser(1, 0, "pw123456");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.login(user.getUsername(), "wrong-password"));
        assertEquals("密码错误", ex.getMessage());
    }

    @Test
    @DisplayName("登录成功：token 携带 userId/username，无租户时兜底 ROLE_USER")
    void issuesTokenForValidCredentials() {
        SysUser user = createUser(1, 0, "pw123456");

        String token = sysUserService.login(user.getUsername(), "pw123456");

        assertNotNull(token);
        assertEquals(user.getId(), jwtTokenUtils.getUserIdFromToken(token));
        assertEquals(user.getUsername(), jwtTokenUtils.getUsernameFromToken(token));
        assertEquals("ROLE_USER", jwtTokenUtils.getRoleFromToken(token));
        assertFalse(jwtTokenUtils.getIsAdminFromToken(token));
    }

    @Test
    @DisplayName("超级管理员登录：isAdmin 声明为 true 且角色包含 ROLE_SUPER_ADMIN")
    void marksSuperAdminInToken() {
        SysUser user = createUser(1, 1, "pw123456");

        String token = sysUserService.login(user.getUsername(), "pw123456");

        assertTrue(jwtTokenUtils.getIsAdminFromToken(token));
        assertTrue(jwtTokenUtils.getRoleFromToken(token).contains("ROLE_SUPER_ADMIN"));
    }

    @Test
    @DisplayName("登录成功会刷新 last_login_time")
    void updatesLastLoginTime() {
        SysUser user = createUser(1, 0, "pw123456");

        sysUserService.login(user.getUsername(), "pw123456");

        SysUser reloaded = sysUserMapper.selectById(user.getId());
        assertNotNull(reloaded.getLastLoginTime());
    }

    private SysUser createUser(int status, int isAdmin, String rawPassword) {
        SysUser user = new SysUser();
        user.setUsername("ut_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        user.setPassword(PasswordUtils.encrypt(rawPassword));
        user.setRealName("单元测试用户");
        user.setStatus(status);
        user.setIsAdmin(isAdmin);
        sysUserMapper.insert(user);
        return user;
    }
}
