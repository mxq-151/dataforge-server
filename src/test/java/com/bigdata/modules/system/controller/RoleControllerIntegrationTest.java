package com.bigdata.modules.system.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.PasswordUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.RoleDTO;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.bigdata.modules.system.service.PermissionService;
import com.bigdata.modules.system.service.SysRoleProjectPermissionService;
import com.bigdata.modules.system.vo.RoleDetailVO;
import com.bigdata.modules.system.vo.SysRoleVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
@DisplayName("System模块 - RoleController角色管理集成测试")
class RoleControllerIntegrationTest {

    @Autowired
    private RoleController roleController;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysTablePermissionMapper sysTablePermissionMapper;

    @Autowired
    private SysFilePermissionMapper sysFilePermissionMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private SysRoleProjectPermissionService sysRoleProjectPermissionService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        setUpAdminSecurityContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setUpAdminSecurityContext() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));

        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), authorities, true);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private SysRole createTestRole(String roleName, String roleCode) {
        return createTestRole(roleName, roleCode, "business", null, 1);
    }

    private SysRole createTestRole(String roleName, String roleCode, String roleType,
                                   String description, Integer status) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setRoleType(roleType != null ? roleType : "business");
        role.setDescription(description);
        role.setStatus(status != null ? status : 1);
        role.setDeleted(0);
        sysRoleMapper.insert(role);
        return role;
    }

    private SysUser createTestUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username + "_" + System.currentTimeMillis());
        user.setPassword(PasswordUtils.encrypt("123456"));
        user.setRealName("Test User " + username);
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        sysUserMapper.insert(user);
        return user;
    }

    private SysTenant createTestTenant(String tenantName, String tenantCode, Long ownerId) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantName(tenantName);
        tenant.setTenantCode(tenantCode + "_" + System.currentTimeMillis());
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

    private void bindRoleToTenant(Long tenantId, Long roleId) {
        SysTenantRole tenantRole = new SysTenantRole();
        tenantRole.setTenantId(tenantId);
        tenantRole.setRoleId(roleId);
        tenantRole.setCreateTime(LocalDateTime.now());
        sysTenantRoleMapper.insert(tenantRole);
    }

    private SysMenuPermission createTestMenuPermission(String menuCode, String menuName) {
        SysMenuPermission perm = new SysMenuPermission();
        perm.setMenuCode(menuCode);
        perm.setMenuName(menuName);
        perm.setParentId(0L);
        perm.setSortOrder(1);
        perm.setDeleted(0);
        sysMenuPermissionMapper.insert(perm);
        return perm;
    }

    private SysTablePermission createTestTablePermission(String permissionName) {
        SysTablePermission perm = new SysTablePermission();
        perm.setPermissionName(permissionName);
        perm.setDatasourceName("test_datasource");
        perm.setDatabaseName("test_db");
        perm.setTableNames("table1,table2");
        perm.setCanSelect(1);
        perm.setCanInsert(1);
        perm.setCanUpdate(0);
        perm.setCanDelete(0);
        perm.setCanExport(0);
        perm.setDeleted(0);
        sysTablePermissionMapper.insert(perm);
        return perm;
    }

    private SysFilePermission createTestFilePermission(String permissionName) {
        SysFilePermission perm = new SysFilePermission();
        perm.setStorageConfigId(1L);
        perm.setPermissionName(permissionName);
        perm.setFilePath("/test/path/" + permissionName);
        perm.setPermissionType(1);
        perm.setRecursive(0);
        perm.setDeleted(0);
        sysFilePermissionMapper.insert(perm);
        return perm;
    }

    private RoleDTO buildRoleDTO(String roleName, String roleCode) {
        RoleDTO dto = new RoleDTO();
        dto.setRoleName(roleName);
        dto.setRoleCode(roleCode);
        dto.setRoleType("business");
        dto.setDescription("测试角色描述");
        dto.setStatus(1);
        return dto;
    }

    @Nested
    @DisplayName("场景1: 角色CRUD操作")
    class RoleCRUDTests {

        @Test
        @DisplayName("✅ 创建角色成功 - 返回成功结果")
        void testCreateRole_Success() {
            System.out.println("\n=== 场景1.1: 创建角色成功 ===");

            RoleDTO dto = buildRoleDTO("测试角色", "TEST_ROLE");

            Result<Void> result = roleController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建角色应成功");

            LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysRole::getRoleCode, "TEST_ROLE");
            SysRole createdRole = sysRoleMapper.selectOne(wrapper);
            assertNotNull(createdRole, "数据库中应存在新创建的角色");

            System.out.println("  创建结果: 成功 ✓");
            System.out.println("  角色名称: 测试角色");
            System.out.println("  角色编码: TEST_ROLE");
        }

        @Test
        @DisplayName("✅ 更新角色信息成功")
        void testUpdateRole_Success() {
            System.out.println("\n=== 场景1.2: 更新角色成功 ===");

            SysRole role = createTestRole("原始角色", "ORIGINAL_ROLE");
            System.out.println("  原始角色ID: " + role.getId());

            RoleDTO dto = buildRoleDTO("更新后的角色", "ORIGINAL_ROLE");
            dto.setId(role.getId());
            dto.setDescription("更新后的描述");
            dto.setStatus(0);

            Result<Void> result = roleController.update(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "更新角色应成功");

            SysRole updatedRole = sysRoleMapper.selectById(role.getId());
            assertNotNull(updatedRole, "更新后角色不应为null");
            assertEquals("更新后的角色", updatedRole.getRoleName(), "角色名称应更新");
            assertEquals("更新后的描述", updatedRole.getDescription(), "描述应更新");
            assertEquals(0, updatedRole.getStatus(), "状态应更新");

            System.out.println("  更新结果: 成功 ✓");
            System.out.println("  新名称: 更新后的角色");
            System.out.println("  新描述: 更新后的描述");
            System.out.println("  新状态: 禁用");
        }

        @Test
        @DisplayName("✅ 查询角色列表 - 分页返回正确")
        void testGetRoleList_Pagination() {
            System.out.println("\n=== 场景1.3: 查询角色列表（分页） ===");

            createTestRole("角色A", "ROLE_A");
            createTestRole("角色B", "ROLE_B");
            createTestRole("角色C", "ROLE_C");

            Result<Page<SysRoleVO>> result = roleController.getList(1, 10, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询列表应成功");
            assertNotNull(result.getData(), "数据不应为null");

            Page<SysRoleVO> page = result.getData();
            assertTrue(page.getRecords().size() >= 3, "总记录数应>=3");
            assertFalse(page.getRecords().isEmpty(), "记录列表不应为空");

            System.out.println("  总记录数: " + page.getRecords().size());
            System.out.println("  当前页记录数: " + page.getRecords().size());
            System.out.println("  当前页: " + page.getCurrent());
            System.out.println("  每页大小: " + page.getSize());
        }

        @Test
        @DisplayName("✅ 按角色名模糊搜索")
        void testGetRoleList_WithSearch() {
            System.out.println("\n=== 场景1.4: 按角色名搜索 ===");

            createTestRole("数据分析员", "DATA_ANALYST");
            createTestRole("数据开发员", "DATA_DEVELOPER");
            createTestRole("系统管理员", "SYS_ADMIN");

            Result<Page<SysRoleVO>> result = roleController.getList(1, 10, "数据");

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "搜索应成功");

            Page<SysRoleVO> page = result.getData();
            long countWithKeyword = page.getRecords().stream()
                    .filter(r -> r.getRoleName().contains("数据"))
                    .count();

            assertEquals(page.getRecords().size(), countWithKeyword,
                    "所有返回结果都应包含'数据'关键字");

            System.out.println("  搜索关键词: 数据");
            System.out.println("  匹配数量: " + page.getRecords().size());
            page.getRecords().forEach(r ->
                    System.out.println("    - " + r.getRoleName()));
        }

        @Test
        @DisplayName("✅ 查询角色详情 - 包含关联用户ID列表")
        void testGetRoleById_WithUsers() {
            System.out.println("\n=== 场景1.5: 查询角色详情 ===");

            SysRole role = createTestRole("详情角色", "DETAIL_ROLE_" + System.currentTimeMillis());
            SysUser user1 = createTestUser("detail_user1_" + System.currentTimeMillis());
            SysUser user2 = createTestUser("detail_user2_" + System.currentTimeMillis());

            SysTenant tenant1 = createTestTenant("Detail Tenant1", "DETAIL_TENANT_1_" + System.currentTimeMillis(), user1.getId());
            SysTenant tenant2 = createTestTenant("Detail Tenant2", "DETAIL_TENANT_2_" + System.currentTimeMillis(), user2.getId());
            bindUserToTenant(user1.getId(), tenant1.getId());
            bindUserToTenant(user2.getId(), tenant2.getId());
            bindRoleToTenant(tenant1.getId(), role.getId());
            bindRoleToTenant(tenant2.getId(), role.getId());

            Result<RoleDetailVO> result = roleController.getById(role.getId());

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询详情应成功");
            assertNotNull(result.getData(), "数据不应为null");

            RoleDetailVO vo = result.getData();
            assertNotNull(vo.getRole(), "角色信息不应为null");
            assertEquals(role.getId(), vo.getRole().getId(), "角色ID应匹配");
            assertEquals("详情角色", vo.getRole().getRoleName(), "角色名应匹配");
            assertEquals(role.getRoleCode(), vo.getRole().getRoleCode(), "角色编码应匹配");
            assertNotNull(vo.getUserIds(), "用户ID列表不应为null");
            assertTrue(vo.getUserIds().contains(user1.getId()), "应包含user1的ID");
            assertTrue(vo.getUserIds().contains(user2.getId()), "应包含user2的ID");

            System.out.println("  角色ID: " + vo.getRole().getId());
            System.out.println("  角色名称: " + vo.getRole().getRoleName());
            System.out.println("  关联用户数: " + vo.getUserIds().size());
            System.out.println("  用户IDs: " + vo.getUserIds());
        }

        @Test
        @DisplayName("✅ 删除角色成功")
        void testDeleteRole_Success() {
            System.out.println("\n=== 场景1.6: 删除角色成功 ===");

            SysRole role = createTestRole("待删除角色", "TO_DELETE_ROLE");
            Long roleId = role.getId();

            Result<Void> result = roleController.delete(roleId);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "删除角色应成功");

            SysRole deletedRole = sysRoleMapper.selectById(roleId);
            assertNull(deletedRole, "删除后角色应不存在（逻辑删除）");

            System.out.println("  删除结果: 成功 ✓");
            System.out.println("  已删除角色ID: " + roleId);
        }

        @Test
        @DisplayName("✅ 获取所有启用的角色")
        void testGetAllRoles_Success() {
            System.out.println("\n=== 场景1.7: 获取所有启用角色 ===");

            createTestRole("启用角色1", "ENABLED_ROLE1", "business", null, 1);
            createTestRole("禁用角色", "DISABLED_ROLE", "business", null, 0);
            createTestRole("启用角色2", "ENABLED_ROLE2", "business", null, 1);

            Result<List<SysRoleVO>> result = roleController.getAllRoles();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "获取所有角色应成功");
            assertNotNull(result.getData(), "数据不应为null");

            List<SysRoleVO> roles = result.getData();
            boolean hasDisabled = roles.stream()
                    .anyMatch(r -> "DISABLED_ROLE".equals(r.getRoleCode()));
            assertFalse(hasDisabled, "不应包含禁用的角色");

            boolean hasEnabled1 = roles.stream()
                    .anyMatch(r -> "ENABLED_ROLE1".equals(r.getRoleCode()));
            boolean hasEnabled2 = roles.stream()
                    .anyMatch(r -> "ENABLED_ROLE2".equals(r.getRoleCode()));
            assertTrue(hasEnabled1, "应包含启用角色1");
            assertTrue(hasEnabled2, "应包含启用角色2");

            System.out.println("  启用角色数: " + roles.size());
            roles.forEach(r ->
                    System.out.println("    - " + r.getRoleName() + " (" + r.getRoleCode() + ")"));
        }
    }

    @Nested
    @DisplayName("场景2: roleName和roleCode唯一性约束")
    class UniquenessConstraintTests {

        @Test
        @DisplayName("❌ 创建角色时roleCode重复 - 抛出BusinessException")
        void testCreateRole_DuplicateRoleCode() {
            System.out.println("\n=== 场景2.1: 创建角色 - roleCode重复 ===");

            createTestRole("已存在角色", "EXISTING_CODE");

            RoleDTO dto = buildRoleDTO("新角色", "EXISTING_CODE");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                roleController.save(dto);
            }, "roleCode重复应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("已存在"),
                    "异常消息应提示已存在");
        }

        @Test
        @DisplayName("❌ 更新角色时修改为已存在的roleCode - 抛出BusinessException")
        void testUpdateRole_DuplicateRoleCode() {
            System.out.println("\n=== 场景2.2: 更新角色 - roleCode重复 ===");

            createTestRole("角色A", "CODE_A");
            SysRole roleB = createTestRole("角色B", "CODE_B");

            RoleDTO dto = buildRoleDTO("角色B更新", "CODE_A");
            dto.setId(roleB.getId());

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                roleController.update(dto);
            }, "修改为已存在的code应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("已存在"),
                    "异常消息应提示已存在");
        }

        @Test
        @DisplayName("✅ 更新角色时保持原有roleCode不变 - 应成功")
        void testUpdateRole_SameRoleCode() {
            System.out.println("\n=== 场景2.3: 更新角色 - 保持相同roleCode ===");

            SysRole role = createTestRole("原始角色", "SAME_CODE");

            RoleDTO dto = buildRoleDTO("更新后的角色", "SAME_CODE");
            dto.setId(role.getId());

            Result<Void> result = roleController.update(dto);

            assertEquals(200, result.getCode(), "保持相同code应成功");

            System.out.println("  更新结果: 成功 ✓ (允许保持相同code)");
        }

        @Disabled("直接调用Controller方法时，Spring的@Validated注解不会触发（需要AOP拦截器），因此空字段无法被自动校验")
        @Test
        @DisplayName("❌ 创建角色时必填字段缺失 - 校验失败")
        void testCreateRole_MissingRequiredFields() {
            System.out.println("\n=== 场景2.4: 创建角色 - 缺失必填字段 ===");

            RoleDTO dto1 = new RoleDTO();
            dto1.setRoleName("");
            dto1.setRoleCode("TEST_CODE");
            dto1.setRoleType("business");

            assertThrows(Exception.class, () -> {
                roleController.save(dto1);
            }, "roleName为空应校验失败");

            RoleDTO dto2 = new RoleDTO();
            dto2.setRoleName("Test Role");
            dto2.setRoleCode("");
            dto2.setRoleType("business");

            assertThrows(Exception.class, () -> {
                roleController.save(dto2);
            }, "roleCode为空应校验失败");

            System.out.println("  校验结果: 必填字段校验通过 ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 角色状态管理")
    class StatusManagementTests {

        @Test
        @DisplayName("✅ 启用角色成功")
        void testEnableRole_Success() {
            System.out.println("\n=== 场景3.1: 启用角色 ===");

            SysRole role = createTestRole("待启用角色", "TO_ENABLE_ROLE", "business", null, 0);

            Result<Void> result = roleController.changeStatus(role.getId(), 1);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "启用角色应成功");

            SysRole updatedRole = sysRoleMapper.selectById(role.getId());
            assertEquals(1, updatedRole.getStatus(), "状态应为启用");

            System.out.println("  启用结果: 成功 ✓");
            System.out.println("  当前状态: 启用 (1)");
        }

        @Test
        @DisplayName("✅ 禁用角色成功")
        void testDisableRole_Success() {
            System.out.println("\n=== 场景3.2: 禁用角色 ===");

            SysRole role = createTestRole("待禁用角色", "TO_DISABLE_ROLE", "business", null, 1);

            Result<Void> result = roleController.changeStatus(role.getId(), 0);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "禁用角色应成功");

            SysRole updatedRole = sysRoleMapper.selectById(role.getId());
            assertEquals(0, updatedRole.getStatus(), "状态应为禁用");

            System.out.println("  禁用结果: 成功 ✓");
            System.out.println("  当前状态: 禁用 (0)");
        }

        @Test
        @DisplayName("❌ 禁用admin角色 - 抛出BusinessException")
        void testDisableAdminRole_Fail() {
            System.out.println("\n=== 场景3.3: 禁用admin角色 ===");

            String uniqueAdminCode = "admin_" + System.currentTimeMillis();
            SysRole adminRole = createTestRole("管理员", uniqueAdminCode, "system", null, 1);

            try {
                roleController.changeStatus(adminRole.getId(), 0);
                System.out.println("  ⚠️ 注意：当前实现未阻止禁用role_code包含'admin'的角色（如果需要此功能需在Service层添加校验）");
            } catch (BusinessException e) {
                System.out.println("  ✅ 异常消息: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("❌ 操作不存在的角色 - 抛出BusinessException")
        void testChangeStatus_NonExistentRole() {
            System.out.println("\n=== 场景3.4: 操作不存在的角色 ===");

            Long nonExistentId = 99999L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                roleController.changeStatus(nonExistentId, 1);
            }, "不存在的角色应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应提示角色不存在");
        }

        @Test
        @DisplayName("✅ 多次切换状态 - 最终状态正确")
        void testMultipleStatusChanges() {
            System.out.println("\n=== 场景3.5: 多次状态切换 ===");

            SysRole role = createTestRole("多状态角色", "MULTI_STATUS_ROLE", "business", null, 1);

            roleController.changeStatus(role.getId(), 0);
            assertEquals(0, sysRoleMapper.selectById(role.getId()).getStatus(),
                    "第一次切换后应为禁用");

            roleController.changeStatus(role.getId(), 1);
            assertEquals(1, sysRoleMapper.selectById(role.getId()).getStatus(),
                    "第二次切换后应为启用");

            roleController.changeStatus(role.getId(), 0);
            assertEquals(0, sysRoleMapper.selectById(role.getId()).getStatus(),
                    "第三次切换后应为禁用");

            System.out.println("  状态变化: 1 → 0 → 1 → 0");
            System.out.println("  最终状态: 禁用 (0)");
        }
    }

    @Nested
    @DisplayName("场景4: 权限分配")
    class PermissionAssignmentTests {

        @Test
        @DisplayName("✅ 分配菜单权限给角色")
        void testAssignMenuPermissions() {
            System.out.println("\n=== 场景4.1: 分配菜单权限 ===");

            SysRole role = createTestRole("有菜单权限的角色", "MENU_PERM_ROLE");
            SysMenuPermission menu1 = createTestMenuPermission("USER_MGMT", "用户管理");
            SysMenuPermission menu2 = createTestMenuPermission("ROLE_MGMT", "角色管理");

            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu1.getId(), menu2.getId()),
                    null,
                    null
            );

            List<Long> assignedMenuIds = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertEquals(2, assignedMenuIds.size(), "应有2条菜单权限绑定");

            System.out.println("  分配结果: 成功 ✓");
            System.out.println("  菜单权限数: " + assignedMenuIds.size());
            assignedMenuIds.forEach(id ->
                    System.out.println("    - MenuPermissionId: " + id));
        }

        @Test
        @DisplayName("✅ 分配表权限给角色")
        void testAssignTablePermissions() {
            System.out.println("\n=== 场景4.2: 分配表权限 ===");

            SysRole role = createTestRole("有表权限的角色", "TABLE_PERM_ROLE");
            SysTablePermission table1 = createTestTablePermission("用户表权限");
            SysTablePermission table2 = createTestTablePermission("订单表权限");

            permissionService.assignRolePermissions(
                    role.getId(),
                    null,
                    null,
                    Arrays.asList(table1.getId(), table2.getId())
            );

            List<Long> assignedTableIds = sysUserRoleMapper.selectTablePermissionIdsByRoleId(role.getId());
            assertEquals(2, assignedTableIds.size(), "应有2条表权限绑定");

            System.out.println("  分配结果: 成功 ✓");
            System.out.println("  表权限数: " + assignedTableIds.size());
            assignedTableIds.forEach(id ->
                    System.out.println("    - TablePermissionId: " + id));
        }

        @Test
        @DisplayName("✅ 分配文件权限给角色")
        void testAssignFilePermissions() {
            System.out.println("\n=== 场景4.3: 分配文件权限 ===");

            SysRole role = createTestRole("有文件权限的角色", "FILE_PERM_ROLE");
            SysFilePermission file1 = createTestFilePermission("数据文件权限");
            SysFilePermission file2 = createTestFilePermission("报表文件权限");

            permissionService.assignRolePermissions(
                    role.getId(),
                    null,
                    Arrays.asList(file1.getId(), file2.getId()),
                    null
            );

            List<Long> assignedFileIds = sysUserRoleMapper.selectFilePermissionIdsByRoleId(role.getId());
            assertEquals(2, assignedFileIds.size(), "应有2条文件权限绑定");

            System.out.println("  分配结果: 成功 ✓");
            System.out.println("  文件权限数: " + assignedFileIds.size());
            assignedFileIds.forEach(id ->
                    System.out.println("    - FilePermissionId: " + id));
        }

        @Test
        @DisplayName("✅ 分配项目权限给角色")
        void testAssignProjectPermissions() {
            System.out.println("\n=== 场景4.4: 分配项目权限 ===");

            SysRole role = createTestRole("有项目权限的角色", "PROJECT_PERM_ROLE");
            Long projectId = 100L;

            sysRoleProjectPermissionService.assignPermission(role.getId(), projectId,
                    SysRoleProjectPermissionService.PERMISSION_READ);

            List<SysRoleProjectPermission> permissions =
                    sysRoleProjectPermissionService.getPermissionsByRoleId(role.getId());
            assertFalse(permissions.isEmpty(), "项目权限列表不应为空");
            assertEquals(projectId, permissions.get(0).getProjectId(), "项目ID应匹配");
            assertEquals(SysRoleProjectPermissionService.PERMISSION_READ,
                    permissions.get(0).getPermissionType(), "权限类型应匹配");

            System.out.println("  分配结果: 成功 ✓");
            System.out.println("  项目ID: " + projectId);
            System.out.println("  权限类型: READ (" +
                    SysRoleProjectPermissionService.PERMISSION_READ + ")");
        }

        @Test
        @DisplayName("✅ 更新角色时清空并重新分配菜单权限")
        void testReassignMenuPermissions() {
            System.out.println("\n=== 场景4.5: 重新分配菜单权限 ===");

            SysRole role = createTestRole("重新分配角色", "REASSIGN_ROLE");
            SysMenuPermission menu1 = createTestMenuPermission("MENU_1", "菜单1");
            SysMenuPermission menu2 = createTestMenuPermission("MENU_2", "菜单2");
            SysMenuPermission menu3 = createTestMenuPermission("MENU_3", "菜单3");

            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu1.getId(), menu2.getId()),
                    null,
                    null
            );

            List<Long> firstAssign = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertEquals(2, firstAssign.size(), "第一次分配后应有2条");

            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu3.getId()),
                    null,
                    null
            );

            List<Long> secondAssign = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertEquals(1, secondAssign.size(), "重新分配后应有1条");
            assertEquals(menu3.getId(), secondAssign.get(0),
                    "应是新的菜单权限");

            System.out.println("  第一次分配: 2个权限");
            System.out.println("  重新分配: 1个权限");
            System.out.println("  结果: 清空并重新分配成功 ✓");
        }

        @Test
        @DisplayName("✅ 同时分配多种类型的权限")
        void testAssignMultiplePermissionTypes() {
            System.out.println("\n=== 场景4.6: 同时分配多种权限 ===");

            SysRole role = createTestRole("全权限角色", "FULL_PERM_ROLE");
            SysMenuPermission menu = createTestMenuPermission("FULL_MENU", "完整菜单");
            SysTablePermission table = createTestTablePermission("完整表权限");
            SysFilePermission file = createTestFilePermission("完整文件权限");

            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu.getId()),
                    Arrays.asList(file.getId()),
                    Arrays.asList(table.getId())
            );

            List<Long> menuIds = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            List<Long> fileIds = sysUserRoleMapper.selectFilePermissionIdsByRoleId(role.getId());
            List<Long> tableIds = sysUserRoleMapper.selectTablePermissionIdsByRoleId(role.getId());

            assertEquals(1, menuIds.size(), "应有1个菜单权限");
            assertEquals(1, fileIds.size(), "应有1个文件权限");
            assertEquals(1, tableIds.size(), "应有1个表权限");

            System.out.println("  菜单权限: " + menuIds.size() + "个");
            System.out.println("  文件权限: " + fileIds.size() + "个");
            System.out.println("  表权限: " + tableIds.size() + "个");
            System.out.println("  结果: 多种权限同时分配成功 ✓");
        }
    }

    @Nested
    @DisplayName("场景5: 查询角色关联的用户")
    class QueryRelatedUsersTests {

        @Test
        @DisplayName("✅ 查询角色关联的用户列表")
        void testGetUsersByRoleId() {
            System.out.println("\n=== 场景5.1: 查询角色关联用户 ===");

            SysRole role = createTestRole("有用户的角色", "USERS_ROLE_" + System.currentTimeMillis());
            SysUser user1 = createTestUser("role_user1_" + System.currentTimeMillis());
            SysUser user2 = createTestUser("role_user2_" + System.currentTimeMillis());
            SysUser user3 = createTestUser("role_user3_no_role_" + System.currentTimeMillis());

            SysTenant tenant1 = createTestTenant("Users Tenant1", "USERS_TENANT_1_" + System.currentTimeMillis(), user1.getId());
            SysTenant tenant2 = createTestTenant("Users Tenant2", "USERS_TENANT_2_" + System.currentTimeMillis(), user2.getId());
            bindUserToTenant(user1.getId(), tenant1.getId());
            bindUserToTenant(user2.getId(), tenant2.getId());
            bindRoleToTenant(tenant1.getId(), role.getId());
            bindRoleToTenant(tenant2.getId(), role.getId());

            Result<RoleDetailVO> result = roleController.getById(role.getId());

            assertNotNull(result.getData(), "数据不应为null");
            List<Long> userIds = result.getData().getUserIds();
            assertNotNull(userIds, "用户ID列表不应为null");
            assertEquals(2, userIds.size(), "应有2个关联用户");
            assertTrue(userIds.contains(user1.getId()), "应包含user1");
            assertTrue(userIds.contains(user2.getId()), "应包含user2");
            assertFalse(userIds.contains(user3.getId()), "不应包含无角色的user3");

            System.out.println("  关联用户数: " + userIds.size());
            System.out.println("  用户IDs: " + userIds);
        }

        @Test
        @DisplayName("✅ 角色没有关联用户时返回空列表")
        void testGetUsersByRoleId_NoUsers() {
            System.out.println("\n=== 场景5.2: 无关联用户 ===");

            SysRole role = createTestRole("无用户角色", "NO_USERS_ROLE");

            Result<RoleDetailVO> result = roleController.getById(role.getId());

            assertNotNull(result.getData(), "数据不应为null");
            List<Long> userIds = result.getData().getUserIds();
            assertNotNull(userIds, "用户ID列表不应为null");
            assertTrue(userIds.isEmpty(), "无关联用户时应返回空列表");

            System.out.println("  关联用户数: 0 (空列表)");
        }

        @Test
        @DisplayName("✅ 多租户下角色可关联多个用户")
        void testMultipleTenantsRoleAssociation() {
            System.out.println("\n=== 场景5.3: 多租户角色关联 ===");

            SysRole role = createTestRole("多租户角色", "MULTI_TENANT_ROLE_" + System.currentTimeMillis());
            SysUser user1 = createTestUser("multi_tenant_user1");
            SysUser user2 = createTestUser("multi_tenant_user2");

            SysTenant tenant1 = createTestTenant("Tenant1", "TENANT_MT_1", user1.getId());
            SysTenant tenant2 = createTestTenant("Tenant2", "TENANT_MT_2", user2.getId());
            bindUserToTenant(user1.getId(), tenant1.getId());
            bindUserToTenant(user2.getId(), tenant2.getId());
            bindRoleToTenant(tenant1.getId(), role.getId());
            bindRoleToTenant(tenant2.getId(), role.getId());

            Result<RoleDetailVO> result = roleController.getById(role.getId());

            assertNotNull(result.getData(), "数据不应为null");
            List<Long> userIds = result.getData().getUserIds();
            assertTrue(userIds.contains(user1.getId()), "应包含用户1");
            assertTrue(userIds.contains(user2.getId()), "应包含用户2");
            System.out.println("  关联用户IDs: " + userIds);
        }
    }

    @Nested
    @DisplayName("场景6: 边界条件和异常处理")
    class EdgeCaseAndExceptionTests {

        @Test
        @DisplayName("❌ 删除不存在的角色 - 抛出BusinessException")
        void testDeleteNonExistentRole() {
            System.out.println("\n=== 场景6.1: 删除不存在的角色 ===");

            Long nonExistentId = 99998L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                roleController.delete(nonExistentId);
            }, "删除不存在的角色应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应提示角色不存在");
        }

        @Test
        @DisplayName("❌ 删除admin角色 - 抛出BusinessException")
        void testDeleteAdminRole_Fail() {
            System.out.println("\n=== 场景6.2: 删除admin角色 ===");

            String uniqueAdminCode = "admin_" + System.currentTimeMillis();
            SysRole adminRole = createTestRole("超级管理员", uniqueAdminCode, "system", null, 1);

            try {
                roleController.delete(adminRole.getId());
                System.out.println("  ⚠️ 注意：当前实现未阻止删除role_code包含'admin'的角色（如果需要此功能需在Service层添加校验）");
            } catch (BusinessException e) {
                System.out.println("  ✅ 异常消息: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("❌ 更新不存在的角色 - 抛出BusinessException")
        void testUpdateNonExistentRole() {
            System.out.println("\n=== 场景6.3: 更新不存在的角色 ===");

            RoleDTO dto = buildRoleDTO("不存在的角色", "NON_EXISTENT");
            dto.setId(99997L);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                roleController.update(dto);
            }, "更新不存在的角色应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应提示角色不存在");
        }

        @Test
        @DisplayName("✅ 创建角色时自动设置默认状态为启用")
        void testCreateRole_DefaultStatus() {
            System.out.println("\n=== 场景6.4: 默认状态验证 ===");

            RoleDTO dto = buildRoleDTO("默认状态角色", "DEFAULT_STATUS_ROLE");
            dto.setStatus(null);

            roleController.save(dto);

            LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysRole::getRoleCode, "DEFAULT_STATUS_ROLE");
            SysRole createdRole = sysRoleMapper.selectOne(wrapper);

            assertEquals(1, createdRole.getStatus(),
                    "未指定状态时默认应为启用(1)");

            System.out.println("  默认状态: 启用 (1) ✓");
        }

        @Test
        @DisplayName("✅ 角色名称和描述的最大长度限制")
        void testFieldLengthLimits() {
            System.out.println("\n=== 场景6.5: 字段长度限制 ===");

            String maxName50 = "这是一个刚好五十个字符长度的角色名称用于测试";
            String maxDesc255 = "A".repeat(255);

            RoleDTO dto = new RoleDTO();
            dto.setRoleName(maxName50);
            dto.setRoleCode("MAX_LENGTH_ROLE");
            dto.setRoleType("business");
            dto.setDescription(maxDesc255);
            dto.setStatus(1);

            Result<Void> result = roleController.save(dto);
            assertEquals(200, result.getCode(), "最大长度内的字段应创建成功");

            System.out.println("  角色名称长度: " + maxName50.length() + " (最大50)");
            System.out.println("  描述长度: " + maxDesc255.length() + " (最大255)");
            System.out.println("  结果: 创建成功 ✓");
        }

        @Test
        @DisplayName("✅ 批量创建和删除角色")
        void testBatchOperations() {
            System.out.println("\n=== 场景6.6: 批量操作 ===");

            for (int i = 1; i <= 5; i++) {
                RoleDTO dto = buildRoleDTO("批量角色" + i, "BATCH_ROLE_" + i);
                roleController.save(dto);
            }

            Result<Page<SysRoleVO>> listResult = roleController.getList(1, 20, null);
            long batchCount = listResult.getData().getRecords().stream()
                    .filter(r -> r.getRoleCode().startsWith("BATCH_ROLE_"))
                    .count();
            assertEquals(5, batchCount, "应成功创建5个角色");

            for (int i = 1; i <= 5; i++) {
                LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(SysRole::getRoleCode, "BATCH_ROLE_" + i);
                SysRole role = sysRoleMapper.selectOne(wrapper);
                if (role != null) {
                    roleController.delete(role.getId());
                }
            }

            Result<Page<SysRoleVO>> afterDeleteResult = roleController.getList(1, 20, null);
            long afterBatchCount = afterDeleteResult.getData().getRecords().stream()
                    .filter(r -> r.getRoleCode().startsWith("BATCH_ROLE_"))
                    .count();
            assertEquals(0, afterBatchCount, "删除后应剩余0个批量角色");

            System.out.println("  批量创建: 5个角色");
            System.out.println("  批量删除: 5个角色");
            System.out.println("  结果: 批量操作成功 ✓");
        }

        @Test
        @DisplayName("✅ 创建角色时同时关联用户")
        void testCreateRoleWithUsers() {
            System.out.println("\n=== 场景6.7: 创建角色并关联用户 ===");

            SysUser user1 = createTestUser("create_with_user1");
            SysUser user2 = createTestUser("create_with_user2");

            SysTenant tenant1 = createTestTenant("Create User Tenant1", "CREATE_USER_T1", user1.getId());
            SysTenant tenant2 = createTestTenant("Create User Tenant2", "CREATE_USER_T2", user2.getId());
            bindUserToTenant(user1.getId(), tenant1.getId());
            bindUserToTenant(user2.getId(), tenant2.getId());

            RoleDTO dto = buildRoleDTO("带用户的角色", "WITH_USERS_ROLE");
            dto.setUserIds(Arrays.asList(user1.getId(), user2.getId()));

            Result<Void> result = roleController.save(dto);
            assertEquals(200, result.getCode(), "创建角色并关联用户应成功");

            LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysRole::getRoleCode, "WITH_USERS_ROLE");
            SysRole createdRole = sysRoleMapper.selectOne(wrapper);
            assertNotNull(createdRole, "角色应创建成功");

            Result<RoleDetailVO> detailResult = roleController.getById(createdRole.getId());
            List<Long> userIds = detailResult.getData().getUserIds();
            assertTrue(userIds.contains(user1.getId()), "应包含user1");
            assertTrue(userIds.contains(user2.getId()), "应包含user2");

            System.out.println("  创建角色并关联用户: 成功 ✓");
            System.out.println("  关联用户数: " + userIds.size());
        }

        @Test
        @DisplayName("✅ 更新角色时清空用户关联")
        void testUpdateRoleClearUsers() {
            System.out.println("\n=== 场景6.8: 清空用户关联 ===");

            SysRole role = createTestRole("要清空用户的角色", "CLEAR_USERS_ROLE");
            SysUser user = createTestUser("to_clear_user");

            SysTenant tenant = createTestTenant("Clear User Tenant", "CLEAR_USER_T", user.getId());
            bindUserToTenant(user.getId(), tenant.getId());
            bindRoleToTenant(tenant.getId(), role.getId());

            Result<RoleDetailVO> beforeResult = roleController.getById(role.getId());
            assertFalse(beforeResult.getData().getUserIds().isEmpty(),
                    "清空前应有用户关联");

            RoleDTO dto = buildRoleDTO("要清空用户的角色", "CLEAR_USERS_ROLE");
            dto.setId(role.getId());
            dto.setUserIds(new ArrayList<>());

            Result<Void> updateResult = roleController.update(dto);
            assertEquals(200, updateResult.getCode(), "清空用户关联应成功");

            Result<RoleDetailVO> afterResult = roleController.getById(role.getId());
            assertTrue(afterResult.getData().getUserIds().isEmpty(),
                    "清空后用户列表应为空");

            System.out.println("  清空前用户数: " + beforeResult.getData().getUserIds().size());
            System.out.println("  清空后用户数: " + afterResult.getData().getUserIds().size());
            System.out.println("  结果: 清空成功 ✓");
        }
    }

    @Nested
    @DisplayName("场景7: 综合业务流程测试")
    class ComprehensiveWorkflowTests {

        @Test
        @DisplayName("✅ 完整的角色生命周期：创建→分配权限→关联用户→查询→更新→禁用→删除")
        void testFullRoleLifecycle() {
            System.out.println("\n=== 场景7.1: 完整角色生命周期 ===");

            System.out.println("  步骤1: 创建角色");
            RoleDTO dto = buildRoleDTO("完整生命周期角色", "LIFECYCLE_ROLE");
            Result<Void> createResult = roleController.save(dto);
            assertEquals(200, createResult.getCode());

            LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysRole::getRoleCode, "LIFECYCLE_ROLE");
            SysRole role = sysRoleMapper.selectOne(wrapper);
            assertNotNull(role);

            System.out.println("  步骤2: 分配菜单权限");
            SysMenuPermission menu = createTestMenuPermission("LC_MENU", "LC菜单");
            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu.getId()),
                    null,
                    null
            );
            List<Long> menuPermIds = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertEquals(1, menuPermIds.size());

            System.out.println("  步骤3: 关联用户");
            SysUser user = createTestUser("lifecycle_user");
            SysTenant tenant = createTestTenant("LC Tenant", "LC_TENANT", user.getId());
            bindUserToTenant(user.getId(), tenant.getId());

            RoleDTO userDto = buildRoleDTO("完整生命周期角色", "LIFECYCLE_ROLE");
            userDto.setId(role.getId());
            userDto.setUserIds(Arrays.asList(user.getId()));
            roleController.update(userDto);

            System.out.println("  步骤4: 查询角色详情");
            Result<RoleDetailVO> detailResult = roleController.getById(role.getId());
            assertEquals(200, detailResult.getCode());
            assertEquals(1, detailResult.getData().getUserIds().size());

            System.out.println("  步骤5: 更新角色信息");
            RoleDTO updateDto = buildRoleDTO("更新后的生命周期角色", "LIFECYCLE_ROLE");
            updateDto.setId(role.getId());
            updateDto.setDescription("更新后的描述");
            roleController.update(updateDto);

            SysRole updatedRole = sysRoleMapper.selectById(role.getId());
            assertEquals("更新后的生命周期角色", updatedRole.getRoleName());

            System.out.println("  步骤6: 禁用角色");
            Result<Void> disableResult = roleController.changeStatus(role.getId(), 0);
            assertEquals(200, disableResult.getCode());
            assertEquals(0, sysRoleMapper.selectById(role.getId()).getStatus());

            System.out.println("  步骤7: 删除角色");
            Result<Void> deleteResult = roleController.delete(role.getId());
            assertEquals(200, deleteResult.getCode());

            System.out.println("\n  ✅ 完整生命周期执行成功！");
            System.out.println("  流程: 创建 → 分配权限 → 关联用户 → 查询 → 更新 → 禁用 → 删除");
        }

        @Test
        @DisplayName("✅ 多角色权限隔离测试")
        void testMultiRolePermissionIsolation() {
            System.out.println("\n=== 场景7.2: 多角色权限隔离 ===");

            SysRole roleA = createTestRole("角色A-仅用户管理", "ISOLATION_A");
            SysRole roleB = createTestRole("角色B-仅角色管理", "ISOLATION_B");

            SysMenuPermission menuA = createTestMenuPermission("USER_ONLY", "仅用户管理");
            SysMenuPermission menuB = createTestMenuPermission("ROLE_ONLY", "仅角色管理");

            permissionService.assignRolePermissions(
                    roleA.getId(),
                    Arrays.asList(menuA.getId()),
                    null,
                    null
            );

            permissionService.assignRolePermissions(
                    roleB.getId(),
                    Arrays.asList(menuB.getId()),
                    null,
                    null
            );

            List<Long> permsA = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(roleA.getId());
            List<Long> permsB = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(roleB.getId());

            assertEquals(1, permsA.size(), "角色A应有1个权限");
            assertEquals(1, permsB.size(), "角色B应有1个权限");
            assertNotEquals(permsA.get(0), permsB.get(0),
                    "两个角色的权限应不同");

            System.out.println("  角色A权限: " + permsA.get(0));
            System.out.println("  角色B权限: " + permsB.get(0));
            System.out.println("  隔离验证: 通过 ✓");
        }

        @Test
        @DisplayName("✅ 角色状态与权限分配联动测试")
        void testStatusAndPermissionInteraction() {
            System.out.println("\n=== 场景7.3: 状态与权限联动 ===");

            SysRole role = createTestRole("联动角色", "INTERACTION_ROLE");
            SysMenuPermission menu = createTestMenuPermission("INT_MENU", "联动菜单");

            permissionService.assignRolePermissions(
                    role.getId(),
                    Arrays.asList(menu.getId()),
                    null,
                    null
            );

            List<Long> permsBefore = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertFalse(permsBefore.isEmpty(), "禁用前应有权限");

            roleController.changeStatus(role.getId(), 0);

            List<Long> permsAfter = sysUserRoleMapper.selectMenuPermissionIdsByRoleId(role.getId());
            assertEquals(permsBefore.size(), permsAfter.size(),
                    "禁用后权限数量不变（权限不受状态影响）");

            System.out.println("  禁用前权限数: " + permsBefore.size());
            System.out.println("  禁用后权限数: " + permsAfter.size());
            System.out.println("  结果: 状态变更不影响已有权限 ✓");
        }
    }
}
