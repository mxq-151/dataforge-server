package com.bigdata.modules.system.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.JwtTokenUtils;
import com.bigdata.common.utils.PasswordUtils;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.dto.LoginDTO;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.service.SysUserService;
import com.bigdata.modules.system.vo.CurrentUserInfoVO;
import com.bigdata.modules.system.vo.LoginVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Rollback;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("System模块 - AuthController认证集成测试")
class AuthControllerIntegrationTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysTenantService sysTenantService;

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysUser createTestUser(String username, String password, Integer isAdmin) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordUtils.encrypt(password));
        user.setRealName("Test User " + username);
        user.setStatus(1);
        user.setIsAdmin(isAdmin);
        user.setDeleted(0);
        sysUserMapper.insert(user);
        return user;
    }

    private SysTenant createTestTenant(String tenantName, String tenantCode, Long ownerId) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantName(tenantName);
        tenant.setTenantCode(tenantCode);
        tenant.setStatus(1);
        tenant.setOwnerId(ownerId);
        tenant.setPassword("tenant_password");
        tenant.setDeleted(0);
        sysTenantMapper.insert(tenant);
        return tenant;
    }

    private void bindUserToTenant(Long userId, Long tenantId) {
        SysTenantUser tenantUser = new SysTenantUser();
        tenantUser.setTenantId(tenantId);
        tenantUser.setUserId(userId);
        tenantUser.setCreateTime(LocalDateTime.now());
        sysTenantUserMapper.insert(tenantUser);
    }

    private SysRole createTestRole(String roleName, String roleCode) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setStatus(1);
        role.setDeleted(0);
        sysRoleMapper.insert(role);
        return role;
    }

    private void bindRoleToTenant(Long tenantId, Long roleId) {
        SysTenantRole tenantRole = new SysTenantRole();
        tenantRole.setTenantId(tenantId);
        tenantRole.setRoleId(roleId);
        tenantRole.setCreateTime(LocalDateTime.now());
        sysTenantRoleMapper.insert(tenantRole);
    }

    private SysMenuPermission createTestMenuPermission(String menuCode) {
        SysMenuPermission perm = new SysMenuPermission();
        perm.setMenuCode(menuCode);
        perm.setMenuName("Menu " + menuCode);
        perm.setParentId(0L);
        perm.setSortOrder(1);
        perm.setDeleted(0);
        sysMenuPermissionMapper.insert(perm);
        return perm;
    }

    private void bindMenuPermissionToRole(Long roleId, Long menuPermId) {
        SysRoleMenuPermission binding = new SysRoleMenuPermission();
        binding.setRoleId(roleId);
        binding.setMenuPermissionId(menuPermId);
        binding.setCreateTime(LocalDateTime.now());
    }

    private void setUpSecurityContext(Long userId, String username, Long tenantId, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            for (String role : roles) {
                authorities.add(new SimpleGrantedAuthority(role));
            }
        }

        CustomUserDetails userDetails = new CustomUserDetails(
                userId, username, tenantId, roles, authorities, true);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Nested
    @DisplayName("场景1: 用户登录成功")
    class LoginSuccessTests {

        @Test
        @DisplayName("✅ 普通用户登录成功 - 返回token和基本信息")
        void testNormalUserLoginSuccess() {
            System.out.println("\n=== 场景1.1: 普通用户登录成功 ===");

            SysUser testUser = createTestUser("test_login_normal", "123456", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_login_normal");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            System.out.println("登录结果: " + (result.getCode() == 200 ? "成功" : "失败"));
            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "登录应成功");
            assertNotNull(result.getData(), "LoginVO不应为null");

            LoginVO vo = result.getData();
            assertNotNull(vo.getToken(), "Token不应为null");
            assertFalse(vo.getToken().isEmpty(), "Token不应为空字符串");

            assertEquals(testUser.getId(), vo.getUserId(), "userId应匹配");
            assertEquals("test_login_normal", vo.getUsername(), "username应匹配");
            assertEquals("Test User test_login_normal", vo.getRealName(), "realName应匹配");

            System.out.println("  Token长度: " + vo.getToken().length());
            System.out.println("  UserId: " + vo.getUserId());
            System.out.println("  Username: " + vo.getUsername());
            System.out.println("  RealName: " + vo.getRealName());

            assertTrue(jwtTokenUtils.validateToken(vo.getToken()), "生成的token应有效");

            Long tokenUserId = jwtTokenUtils.getUserIdFromToken(vo.getToken());
            String tokenUsername = jwtTokenUtils.getUsernameFromToken(vo.getToken());
            assertEquals(testUser.getId(), tokenUserId, "Token中的userId应匹配");
            assertEquals("test_login_normal", tokenUsername, "Token中的username应匹配");
        }

        @Test
        @DisplayName("✅ 管理员用户登录成功 - 包含ROLE_ADMIN角色")
        void testAdminUserLoginSuccess() {
            System.out.println("\n=== 场景1.2: 管理员用户登录成功 ===");

            SysUser adminUser = createTestUser("test_login_admin", "123456", 1);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_login_admin");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertNotNull(result, "结果不应为null");
            assertTrue(result.getCode() == 200, "管理员登录应成功");
            assertNotNull(result.getData(), "LoginVO不应为null");

            LoginVO vo = result.getData();
            assertNotNull(vo.getRoles(), "角色列表不应为null");
            assertFalse(vo.getRoles().isEmpty(), "角色列表不应为空");
            assertTrue(vo.getRoles().contains("ROLE_ADMIN"), "应包含ROLE_ADMIN角色");

            System.out.println("  角色: " + vo.getRoles());
        }

        @Test
        @DisplayName("✅ 用户绑定租户后登录 - 返回租户信息")
        void testUserWithTenantLoginSuccess() {
            System.out.println("\n=== 场景1.3: 绑定租户的用户登录 ===");

            SysUser testUser = createTestUser("test_login_tenant", "123456", 0);
            SysTenant tenant = createTestTenant("Test Tenant Login", "TEST_TENANT_LOGIN", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_login_tenant");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertNotNull(result, "结果不应为null");
            assertTrue(result.getCode() == 200, "带租户的用户登录应成功");
            assertNotNull(result.getData(), "LoginVO不应为null");

            LoginVO vo = result.getData();
            assertEquals(tenant.getId(), vo.getTenantId(), "tenantId应匹配");
            assertEquals("Test Tenant Login", vo.getTenantName(), "tenantName应匹配");

            System.out.println("  TenantId: " + vo.getTenantId());
            System.out.println("  TenantName: " + vo.getTenantName());
        }

        @Test
        @DisplayName("✅ 用户绑定角色后登录 - 角色信息正确返回")
        void testUserWithRolesLoginSuccess() {
            System.out.println("\n=== 场景1.4: 绑定角色的用户登录 ===");

            SysUser testUser = createTestUser("test_login_roles", "123456", 0);
            SysTenant tenant = createTestTenant("Test Tenant Roles", "TEST_TENANT_ROLES", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            String roleCode1 = "ANALYST_" + System.currentTimeMillis();
            String roleCode2 = "DEVELOPER_" + System.currentTimeMillis();
            SysRole role1 = createTestRole("数据分析师", roleCode1);
            SysRole role2 = createTestRole("开发人员", roleCode2);
            bindRoleToTenant(tenant.getId(), role1.getId());
            bindRoleToTenant(tenant.getId(), role2.getId());

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_login_roles");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertNotNull(result, "结果不应为null");
            assertTrue(result.getCode() == 200, "带角色的用户登录应成功");
            assertNotNull(result.getData(), "LoginVO不应为null");

            LoginVO vo = result.getData();
            assertNotNull(vo.getRoles(), "角色列表不应为null");
            assertTrue(vo.getRoles().contains("ROLE_" + roleCode1), "应包含ROLE_" + roleCode1);
            assertTrue(vo.getRoles().contains("ROLE_" + roleCode2), "应包含ROLE_" + roleCode2);

            System.out.println("  角色: " + vo.getRoles());
        }
    }

    @Nested
    @DisplayName("场景2: 用户登录失败")
    class LoginFailureTests {

        @Test
        @DisplayName("❌ 用户名为空 - 抛出校验异常")
        void testLoginWithEmptyUsername() {
            System.out.println("\n=== 场景2.1: 用户名为空 ===");

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("");
            loginDTO.setPassword("123456");

            assertThrows(Exception.class, () -> {
                authController.login(loginDTO);
            }, "用户名为空应抛出异常");
        }

        @Test
        @DisplayName("❌ 密码为空 - 抛出校验异常")
        void testLoginWithEmptyPassword() {
            System.out.println("\n=== 场景2.2: 密码为空 ===");

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_user");
            loginDTO.setPassword("");

            assertThrows(Exception.class, () -> {
                authController.login(loginDTO);
            }, "密码为空应抛出异常");
        }

        @Test
        @DisplayName("❌ 不存在的用户名 - 抛出BusinessException")
        void testLoginWithNonExistentUser() {
            System.out.println("\n=== 场景2.3: 不存在的用户 ===");

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("non_existent_user_12345");
            loginDTO.setPassword("123456");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                authController.login(loginDTO);
            }, "不存在的用户应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
        }

        @Test
        @DisplayName("❌ 密码错误 - 抛出BusinessException")
        void testLoginWithWrongPassword() {
            System.out.println("\n=== 场景2.4: 密码错误 ===");

            SysUser testUser = createTestUser("test_login_wrongpwd", "correct_password", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_login_wrongpwd");
            loginDTO.setPassword("wrong_password");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                authController.login(loginDTO);
            }, "密码错误应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("密码"), "异常消息应包含'密码'");
        }

        @Test
        @DisplayName("❌ 账号被禁用 - 抛出BusinessException")
        void testLoginWithDisabledAccount() {
            System.out.println("\n=== 场景2.5: 账号被禁用 ===");

            SysUser disabledUser = new SysUser();
            disabledUser.setUsername("test_disabled_user");
            disabledUser.setPassword(PasswordUtils.encrypt("123456"));
            disabledUser.setRealName("Disabled User");
            disabledUser.setStatus(0);
            disabledUser.setIsAdmin(0);
            disabledUser.setDeleted(0);
            sysUserMapper.insert(disabledUser);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_disabled_user");
            loginDTO.setPassword("123456");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                authController.login(loginDTO);
            }, "禁用账号应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("禁用"), "异常消息应包含'禁用'");
        }
    }

    @Nested
    @DisplayName("场景3: 获取当前用户信息 (/system/auth/info)")
    class GetUserInfoTests {

        @Test
        @DisplayName("✅ 已登录用户能获取完整信息")
        void testGetUserInfo_Success() {
            System.out.println("\n=== 场景3.1: 已登录用户获取信息 ===");

            SysUser testUser = createTestUser("test_info_success", "123456", 0);
            SysTenant tenant = createTestTenant("Test Tenant Info", "TEST_TENANT_INFO", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            setUpSecurityContext(testUser.getId(), "test_info_success", tenant.getId(),
                    Arrays.asList("ROLE_USER"));

            Result<CurrentUserInfoVO> result = authController.getUserInfo();

            assertNotNull(result, "结果不应为null");
            assertTrue(result.getCode() == 200, "获取用户信息应成功");
            assertNotNull(result.getData(), "CurrentUserInfoVO不应为null");

            CurrentUserInfoVO vo = result.getData();
            assertEquals(testUser.getId(), vo.getId(), "ID应匹配");
            assertEquals("test_info_success", vo.getUsername(), "username应匹配");
            assertEquals("Test User test_info_success", vo.getRealName(), "realName应匹配");
            assertEquals(tenant.getId(), vo.getTenantId(), "tenantId应匹配");
            assertEquals("Test Tenant Info", vo.getTenantName(), "tenantName应匹配");
            assertNotNull(vo.getRoles(), "roles不应为null");
            assertFalse(vo.getRoles().isEmpty(), "roles不应为空");

            System.out.println("  ID: " + vo.getId());
            System.out.println("  Username: " + vo.getUsername());
            System.out.println("  RealName: " + vo.getRealName());
            System.out.println("  TenantId: " + vo.getTenantId());
            System.out.println("  TenantName: " + vo.getTenantName());
            System.out.println("  Roles: " + vo.getRoles());
        }

        @Test
        @DisplayName("✅ 管理员用户获取信息 - 包含超级管理员标识")
        void testGetUserInfo_AdminUser() {
            System.out.println("\n=== 场景3.2: 管理员获取信息 ===");

            SysUser adminUser = createTestUser("test_info_admin", "123456", 1);
            SysTenant tenant = createTestTenant("Test Admin Tenant", "TEST_ADMIN_TENANT", adminUser.getId());
            bindUserToTenant(adminUser.getId(), tenant.getId());

            setUpSecurityContext(adminUser.getId(), "test_info_admin", tenant.getId(),
                    Arrays.asList("ROLE_ADMIN"));

            Result<CurrentUserInfoVO> result = authController.getUserInfo();

            assertNotNull(result, "结果不应为null");
            assertTrue(result.getCode() == 200, "管理员获取信息应成功");
            assertNotNull(result.getData(), "CurrentUserInfoVO不应为null");

            CurrentUserInfoVO vo = result.getData();
            assertTrue(vo.getRoles().contains("超级管理员"), "应包含'超级管理员'角色");

            System.out.println("  Roles: " + vo.getRoles());
        }

        @Test
        @DisplayName("❌ 未登录用户获取信息 - 抛出BusinessException")
        void testGetUserInfo_NotLoggedIn() {
            System.out.println("\n=== 场景3.3: 未登录用户获取信息 ===");

            SecurityContextHolder.clearContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                authController.getUserInfo();
            }, "未登录应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("未登录") || exception.getMessage().contains("过期"),
                    "异常消息应提示未登录或过期");
        }
    }

    @Nested
    @DisplayName("场景4: 多租户场景验证")
    class MultiTenantTests {

        @Test
        @DisplayName("✅ 用户绑定单个租户 - 登录时正确返回租户信息")
        void testSingleTenantBinding() {
            System.out.println("\n=== 场景4.1: 单租户绑定 ===");

            SysUser testUser = createTestUser("test_single_tenant", "123456", 0);
            SysTenant tenant = createTestTenant("Single Tenant", "SINGLE_TENANT", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_single_tenant");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertTrue(result.getCode() == 200, "登录应成功");
            assertEquals(tenant.getId(), result.getData().getTenantId(), "tenantId应匹配");
            assertEquals("Single Tenant", result.getData().getTenantName(), "tenantName应匹配");

            System.out.println("  TenantId: " + result.getData().getTenantId());
            System.out.println("  TenantName: " + result.getData().getTenantName());
        }

        @Test
        @DisplayName("✅ 用户未绑定租户 - 登录时tenantId和tenantName为null")
        void testNoTenantBinding() {
            System.out.println("\n=== 场景4.2: 无租户绑定 ===");

            SysUser testUser = createTestUser("test_no_tenant", "123456", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_no_tenant");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertTrue(result.getCode() == 200, "无租户用户登录应成功");
            assertNull(result.getData().getTenantId(), "无租户时tenantId应为null");
            assertNull(result.getData().getTenantName(), "无租户时tenantName应为null");

            System.out.println("  TenantId: " + result.getData().getTenantId());
            System.out.println("  TenantName: " + result.getData().getTenantName());
        }

        @Test
        @DisplayName("✅ 角色与租户关联查询 - 正确解析角色链路")
        void testRoleTenantAssociation() {
            System.out.println("\n=== 场景4.3: 角色与租户关联 ===");

            SysUser testUser = createTestUser("test_role_tenant_assoc", "123456", 0);
            SysTenant tenant = createTestTenant("Role Tenant", "ROLE_TENANT", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            SysRole role = createTestRole("测试角色", "TEST_ROLE");
            bindRoleToTenant(tenant.getId(), role.getId());

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_role_tenant_assoc");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);

            assertTrue(result.getCode() == 200, "登录应成功");
            assertNotNull(result.getData().getRoles(), "角色列表不应为null");
            assertTrue(result.getData().getRoles().contains("ROLE_TEST_ROLE"),
                    "应包含从租户关联的角色");

            System.out.println("  解析到的角色: " + result.getData().getRoles());
        }
    }

    @Nested
    @DisplayName("场景5: Token生成与验证完整性")
    class TokenIntegrityTests {

        @Test
        @DisplayName("✅ Token包含完整的Claims信息")
        void testTokenClaimsCompleteness() {
            System.out.println("\n=== 场景5.1: Token Claims完整性 ===");

            SysUser testUser = createTestUser("test_token_claims", "123456", 0);
            SysTenant tenant = createTestTenant("Token Tenant", "TOKEN_TENANT", testUser.getId());
            bindUserToTenant(testUser.getId(), tenant.getId());

            SysRole role = createTestRole("Token Role", "TOKEN_ROLE");
            bindRoleToTenant(tenant.getId(), role.getId());

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_token_claims");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);
            String token = result.getData().getToken();

            assertTrue(jwtTokenUtils.validateToken(token), "Token应有效");

            Long userId = jwtTokenUtils.getUserIdFromToken(token);
            String username = jwtTokenUtils.getUsernameFromToken(token);
            String roleStr = jwtTokenUtils.getRoleFromToken(token);
            Long tenantId = jwtTokenUtils.getTenantIdFromToken(token);

            assertEquals(testUser.getId(), userId, "Token中的userId应匹配");
            assertEquals("test_token_claims", username, "Token中的username应匹配");
            assertNotNull(roleStr, "Token中的role不应为null");
            assertTrue(roleStr.contains("ROLE_TOKEN_ROLE"), "role应包含分配的角色");
            assertEquals(tenant.getId(), tenantId, "Token中的tenantId应匹配");

            System.out.println("  Token UserId: " + userId);
            System.out.println("  Token Username: " + username);
            System.out.println("  Token Role: " + roleStr);
            System.out.println("  Token TenantId: " + tenantId);
        }

        @Test
        @DisplayName("✅ Token过期时间设置正确")
        void testTokenExpiration() {
            System.out.println("\n=== 场景5.2: Token过期时间 ===");

            SysUser testUser = createTestUser("test_token_expiry", "123456", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_token_expiry");
            loginDTO.setPassword("123456");

            Result<LoginVO> result = authController.login(loginDTO);
            String token = result.getData().getToken();

            var claims = jwtTokenUtils.getTokenClaims(token);
            Date expiration = claims.getExpiration();
            Date issuedAt = claims.getIssuedAt();

            assertNotNull(expiration, "过期时间不应为null");
            assertNotNull(issuedAt, "签发时间不应为null");
            assertTrue(expiration.after(issuedAt), "过期时间应在签发时间之后");

            long diffInMillis = expiration.getTime() - issuedAt.getTime();
            long diffInHours = diffInMillis / (1000 * 60 * 60);

            System.out.println("  签发时间: " + issuedAt);
            System.out.println("  过期时间: " + expiration);
            System.out.println("  有效期（小时）: " + diffInHours);
            assertTrue(diffInHours > 0, "有效期应大于0");
            assertTrue(diffInHours <= 24, "有效期应在24小时以内（根据配置）");
        }
    }

    @Nested
    @DisplayName("场景6: 边界条件和安全性")
    class EdgeCaseAndSecurityTests {

        @Test
        @DisplayName("✅ 最后登录时间更新")
        void testLastLoginTimeUpdate() {
            System.out.println("\n=== 场景6.1: 最后登录时间更新 ===");

            SysUser testUser = createTestUser("test_lastlogin", "123456", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_lastlogin");
            loginDTO.setPassword("123456");

            Result<LoginVO> resultBefore = authController.login(loginDTO);
            assertTrue(resultBefore.getCode() == 200, "第一次登录应成功");

            SysUser userAfterLogin = sysUserMapper.selectById(testUser.getId());
            assertNotNull(userAfterLogin.getLastLoginTime(), "最后登录时间不应为null");
            System.out.println("  最后登录时间: " + userAfterLogin.getLastLoginTime());
        }

        @Test
        @DisplayName("✅ 同一用户多次登录 - 每次生成不同Token")
        void testMultipleLoginsGenerateDifferentTokens() {
            System.out.println("\n=== 场景6.2: 多次登录生成不同Token ===");

            SysUser testUser = createTestUser("test_multi_login", "123456", 0);

            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("test_multi_login");
            loginDTO.setPassword("123456");

            Result<LoginVO> result1 = authController.login(loginDTO);
            assertTrue(result1.getCode() == 200, "第一次登录应成功");

            try {
                Thread.sleep(1100); // 确保跨过秒边界，使JWT时间戳不同
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Result<LoginVO> result2 = authController.login(loginDTO);

            assertNotNull(result1.getData().getToken(), "第一次Token不应为null");
            assertNotNull(result2.getData().getToken(), "第二次Token不应为null");
            assertNotEquals(result1.getData().getToken(), result2.getData().getToken(),
                    "多次登录应生成不同的Token");

            System.out.println("  Token1长度: " + result1.getData().getToken().length());
            System.out.println("  Token2长度: " + result2.getData().getToken().length());
            System.out.println("  Token1 == Token2: " + result1.getData().getToken().equals(result2.getData().getToken()));
        }

        @Test
        @DisplayName("✅ 密码加密存储 - 数据库中不存明文")
        void testPasswordEncryption() {
            System.out.println("\n=== 场景6.3: 密码加密存储 ===");

            String plainPassword = "my_secure_password_123";
            SysUser testUser = createTestUser("test_pwd_encrypt", plainPassword, 0);

            SysUser fromDB = sysUserMapper.selectById(testUser.getId());
            assertNotEquals(plainPassword, fromDB.getPassword(),
                    "数据库中的密码不应是明文");
            assertEquals(32, fromDB.getPassword().length(),
                    "MD5哈希应为32个字符");
            assertTrue(fromDB.getPassword().matches("[0-9a-f]{32}"),
                    "MD5哈希应只包含十六进制字符[0-9a-f]");
            assertTrue(PasswordUtils.verify(plainPassword, fromDB.getPassword()),
                    "应能通过验证原始密码");

            System.out.println("  明文密码: " + plainPassword);
            System.out.println("  存储的哈希: " + fromDB.getPassword().substring(0, 20) + "...");
            System.out.println("  验证结果: 通过 ✓");
        }
    }
}
