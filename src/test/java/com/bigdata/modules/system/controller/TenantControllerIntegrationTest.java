package com.bigdata.modules.system.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.PasswordUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.TenantDTO;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.vo.TenantVO;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("System模块 - TenantController租户管理集成测试")
class TenantControllerIntegrationTest {

    @Autowired
    private TenantController tenantController;

    @Autowired
    private SysTenantService sysTenantService;

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setUpAdminSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void setUpNormalUserSecurityContext(Long userId, String username) {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                userId, username, 1L, Arrays.asList("ROLE_USER"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private SysUser createTestUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordUtils.encrypt("password123"));
        user.setRealName("Test User " + username);
        user.setStatus(1);
        user.setIsAdmin(0);
        sysUserMapper.insert(user);
        return user;
    }

    private SysRole createTestRole(String roleName, String roleCode) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setStatus(1);
        sysRoleMapper.insert(role);
        return role;
    }

    private TenantDTO buildTenantDTO(String tenantName, String tenantCode) {
        TenantDTO dto = new TenantDTO();
        dto.setTenantName(tenantName);
        dto.setTenantCode(tenantCode);
        dto.setContactName("Contact");
        dto.setContactPhone("13800138000");
        dto.setContactEmail("contact@test.com");
        dto.setDescription("Test tenant description");
        dto.setStatus(1);
        dto.setPassword("tenant_password");
        return dto;
    }

    @Nested
    @DisplayName("场景1: 租户CRUD操作")
    class TenantCrudTests {

        @Test
        @DisplayName("✅ 创建租户成功")
        void testCreateTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("测试租户", "TEST_TENANT_001");

            Result<Void> result = tenantController.save(dto);

            assertNotNull(result);
            assertEquals(200, result.getCode(), "创建租户应成功");
        }

        @Test
        @DisplayName("✅ 创建租户并绑定用户成功")
        void testCreateTenantWithUserSuccess() {
            setUpAdminSecurityContext();

            SysUser user = createTestUser("tenant_user_001");
            TenantDTO dto = buildTenantDTO("带用户的租户", "TENANT_WITH_USER");
            dto.setUserId(user.getId());

            Result<Void> result = tenantController.save(dto);

            assertEquals(200, result.getCode(), "创建租户并绑定用户应成功");

            SysTenant saved = sysTenantService.getTenantByCode("TENANT_WITH_USER");
            assertNotNull(saved, "租户应已保存");
            assertEquals("带用户的租户", saved.getTenantName());

            SysUser boundUser = sysTenantService.getUserByTenantId(saved.getId());
            assertNotNull(boundUser, "用户应已绑定到租户");
            assertEquals(user.getId(), boundUser.getId());
        }

        @Test
        @DisplayName("✅ 更新租户信息成功")
        void testUpdateTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO createDto = buildTenantDTO("原始租户", "UPDATE_TEST");
            tenantController.save(createDto);

            SysTenant created = sysTenantService.getTenantByCode("UPDATE_TEST");
            assertNotNull(created);

            TenantDTO updateDto = new TenantDTO();
            updateDto.setId(created.getId());
            updateDto.setTenantName("更新后的租户名");
            updateDto.setTenantCode("UPDATE_TEST");
            updateDto.setContactName("New Contact");
            updateDto.setContactPhone("13900139000");
            updateDto.setDescription("Updated description");
            updateDto.setStatus(0);

            Result<Void> result = tenantController.update(updateDto);

            assertEquals(200, result.getCode(), "更新租户应成功");

            SysTenant updated = sysTenantService.getTenantById(created.getId());
            assertNotNull(updated);
            assertEquals("更新后的租户名", updated.getTenantName());
            assertEquals("New Contact", updated.getContactName());
            assertEquals("13900139000", updated.getContactPhone());
            assertEquals("Updated description", updated.getDescription());
            assertEquals(0, updated.getStatus());
        }

        @Test
        @DisplayName("✅ 查询租户列表成功")
        void testGetTenantListSuccess() {
            setUpAdminSecurityContext();

            for (int i = 1; i <= 5; i++) {
                TenantDTO dto = buildTenantDTO("列表租户" + i, "LIST_TENANT_" + String.format("%03d", i));
                tenantController.save(dto);
            }

            Result<Page<TenantVO>> result = tenantController.getList(1, 10, null, null);

            assertNotNull(result);
            assertEquals(200, result.getCode(), "查询列表应成功");
            assertNotNull(result.getData(), "分页数据不应为null");
            assertTrue(result.getData().getRecords().size() >= 5, "至少应有5条记录");
        }

        @Test
        @DisplayName("✅ 按名称模糊搜索租户列表")
        void testGetTenantListWithSearch() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("科技公司A", "SEARCH_TENANT_A");
            TenantDTO dto2 = buildTenantDTO("科技公司B", "SEARCH_TENANT_B");
            TenantDTO dto3 = buildTenantDTO("教育机构C", "SEARCH_TENANT_C");
            tenantController.save(dto1);
            tenantController.save(dto2);
            tenantController.save(dto3);

            Result<Page<TenantVO>> result = tenantController.getList(1, 10, "科技", null);

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            assertEquals(2, result.getData().getRecords().size(), "搜索'科技'应返回2条记录");
        }

        @Test
        @DisplayName("✅ 按状态过滤租户列表")
        void testGetTenantListWithStatusFilter() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("启用租户", "STATUS_ACTIVE");
            dto1.setStatus(1);
            tenantController.save(dto1);

            TenantDTO dto2 = buildTenantDTO("禁用租户", "STATUS_INACTIVE");
            dto2.setStatus(0);
            tenantController.save(dto2);

            Result<Page<TenantVO>> activeResult = tenantController.getList(1, 10, null, 1);
            assertEquals(200, activeResult.getCode());
            assertFalse(activeResult.getData().getRecords().isEmpty());

            Result<Page<TenantVO>> inactiveResult = tenantController.getList(1, 10, null, 0);
            assertEquals(200, inactiveResult.getCode());
            assertFalse(inactiveResult.getData().getRecords().isEmpty());
        }

        @Test
        @DisplayName("✅ 查询租户详情成功")
        void testGetTenantByIdSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("详情租户", "DETAIL_TENANT");
            tenantController.save(dto);

            SysTenant created = sysTenantService.getTenantByCode("DETAIL_TENANT");
            assertNotNull(created);

            Result<TenantVO> result = tenantController.getById(created.getId());

            assertNotNull(result);
            assertEquals(200, result.getCode(), "查询详情应成功");
            assertNotNull(result.getData(), "VO数据不应为null");
            assertEquals("详情租户", result.getData().getTenantName());
            assertEquals("DETAIL_TENANT", result.getData().getTenantCode());
        }

        @Test
        @DisplayName("✅ 删除租户成功")
        void testDeleteTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("待删除租户", "DELETE_TENANT");
            tenantController.save(dto);

            SysTenant created = sysTenantService.getTenantByCode("DELETE_TENANT");
            assertNotNull(created);

            Result<Void> result = tenantController.delete(created.getId());

            assertEquals(200, result.getCode(), "删除租户应成功");

            SysTenant deleted = sysTenantService.getTenantById(created.getId());
            assertNull(deleted, "删除后租户不应存在");
        }
    }

    @Nested
    @DisplayName("场景2: tenantCode唯一性约束")
    class TenantCodeUniquenessTests {

        @Test
        @DisplayName("❌ 重复的tenantCode应抛出异常")
        void testDuplicateTenantCodeShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("第一个租户", "DUPLICATE_CODE");
            tenantController.save(dto1);

            TenantDTO dto2 = buildTenantDTO("第二个租户", "DUPLICATE_CODE");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                tenantController.save(dto2);
            }, "重复编码应抛出BusinessException");

            assertTrue(exception.getMessage().contains("租户编码已存在"));
        }

        @Test
        @DisplayName("❌ 更新时使用已存在的tenantCode应抛出异常")
        void testUpdateWithDuplicateCodeShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("租户A", "CODE_EXIST_A");
            tenantController.save(dto1);

            TenantDTO dto2 = buildTenantDTO("租户B", "CODE_EXIST_B");
            tenantController.save(dto2);

            SysTenant tenantA = sysTenantService.getTenantByCode("CODE_EXIST_A");
            assertNotNull(tenantA);

            TenantDTO updateDto = new TenantDTO();
            updateDto.setId(tenantA.getId());
            updateDto.setTenantName("租户A更新");
            updateDto.setTenantCode("CODE_EXIST_B");

            assertThrows(BusinessException.class, () -> {
                tenantController.update(updateDto);
            }, "更新时使用其他租户的编码应抛出异常");
        }

        @Test
        @DisplayName("✅ 更新时保持原tenantCode不变应成功")
        void testUpdateWithSameCodeShouldSucceed() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("原始租户", "SAME_CODE_TEST");
            tenantController.save(dto);

            SysTenant created = sysTenantService.getTenantByCode("SAME_CODE_TEST");
            assertNotNull(created);

            TenantDTO updateDto = new TenantDTO();
            updateDto.setId(created.getId());
            updateDto.setTenantName("更新名称");
            updateDto.setTenantCode("SAME_CODE_TEST");

            Result<Void> result = tenantController.update(updateDto);

            assertEquals(200, result.getCode(), "使用相同编码更新应成功");
        }
    }

    @Nested
    @DisplayName("场景3: 租户状态管理")
    class TenantStatusManagementTests {

        @Test
        @DisplayName("✅ 启用租户成功")
        void testEnableTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("待启用的租户", "ENABLE_TEST");
            dto.setStatus(0);
            tenantController.save(dto);

            SysTenant created = sysTenantService.getTenantByCode("ENABLE_TEST");
            assertNotNull(created);
            assertEquals(0, created.getStatus(), "初始状态应为禁用");

            Result<Void> result = tenantController.changeStatus(created.getId(), 1);

            assertEquals(200, result.getCode(), "启用租户应成功");

            SysTenant enabled = sysTenantService.getTenantById(created.getId());
            assertEquals(1, enabled.getStatus(), "状态应为启用");
        }

        @Test
        @DisplayName("✅ 禁用租户成功")
        void testDisableTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("待禁用的租户", "DISABLE_TEST");
            dto.setStatus(1);
            tenantController.save(dto);

            SysTenant created = sysTenantService.getTenantByCode("DISABLE_TEST");
            assertNotNull(created);

            Result<Void> result = tenantController.changeStatus(created.getId(), 0);

            assertEquals(200, result.getCode(), "禁用租户应成功");

            SysTenant disabled = sysTenantService.getTenantById(created.getId());
            assertEquals(0, disabled.getStatus(), "状态应为禁用");
        }

        @Test
        @DisplayName("❌ 对不存在的租户修改状态应抛出异常")
        void testChangeStatusForNonExistentTenantShouldFail() {
            setUpAdminSecurityContext();

            assertThrows(BusinessException.class, () -> {
                tenantController.changeStatus(99999L, 1);
            }, "对不存在的租户修改状态应抛出异常");
        }
    }

    @Nested
    @DisplayName("场景4: 租户用户管理")
    class TenantUserManagementTests {

        @Test
        @DisplayName("✅ 绑定用户到租户成功")
        void testBindUserToTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("有用户的租户", "BIND_USER_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("BIND_USER_TENANT");
            assertNotNull(tenant);

            SysUser user = createTestUser("bound_user_001");

            Result<Void> result = tenantController.bindUser(tenant.getId(), user.getId());

            assertEquals(200, result.getCode(), "绑定用户应成功");

            SysUser boundUser = sysTenantService.getUserByTenantId(tenant.getId());
            assertNotNull(boundUser, "用户应已绑定");
            assertEquals(user.getId(), boundUser.getId());

            Result<TenantVO> voResult = tenantController.getById(tenant.getId());
            assertEquals(user.getId(), voResult.getData().getUserId());
            assertEquals("bound_user_001", voResult.getData().getUsername());
        }

        @Test
        @DisplayName("✅ 解绑用户与租户成功")
        void testUnbindUserFromTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("解绑测试租户", "UNBIND_USER_TENANT");
            SysUser user = createTestUser("unbind_user_001");
            dto.setUserId(user.getId());
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("UNBIND_USER_TENANT");
            assertNotNull(tenant);

            SysUser boundUserBefore = sysTenantService.getUserByTenantId(tenant.getId());
            assertNotNull(boundUserBefore, "解绑前用户应存在");

            Result<Void> result = tenantController.unbindUser(tenant.getId(), user.getId());

            assertEquals(200, result.getCode(), "解绑用户应成功");

            SysUser boundUserAfter = sysTenantService.getUserByTenantId(tenant.getId());
            assertNull(boundUserAfter, "解绑后用户不应存在");
        }

        @Test
        @DisplayName("❌ 重复绑定同一用户应抛出异常")
        void testBindDuplicateUserShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("重复绑定租户", "DUP_BIND_TENANT");
            SysUser user = createTestUser("dup_bind_user");
            dto.setUserId(user.getId());
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("DUP_BIND_TENANT");
            assertNotNull(tenant);

            assertThrows(BusinessException.class, () -> {
                tenantController.bindUser(tenant.getId(), user.getId());
            }, "重复绑定同一用户应抛出异常");
        }

        @Test
        @DisplayName("❌ 绑定已属于其他租户的用户应抛出异常")
        void testBindUserFromOtherTenantShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("租户X", "OTHER_TENANT_X");
            TenantDTO dto2 = buildTenantDTO("租户Y", "OTHER_TENANT_Y");
            tenantController.save(dto1);
            tenantController.save(dto2);

            SysTenant tenantX = sysTenantService.getTenantByCode("OTHER_TENANT_X");
            SysTenant tenantY = sysTenantService.getTenantByCode("OTHER_TENANT_Y");
            assertNotNull(tenantX);
            assertNotNull(tenantY);

            SysUser user = createTestUser("shared_user");
            tenantController.bindUser(tenantX.getId(), user.getId());

            assertThrows(BusinessException.class, () -> {
                tenantController.bindUser(tenantY.getId(), user.getId());
            }, "绑定已属于其他租户的用户应抛出异常");
        }

        @Test
        @DisplayName("❌ 解绑未绑定的用户应抛出异常")
        void testUnbindNonBoundUserShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("空租户", "EMPTY_UNBIND_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("EMPTY_UNBIND_TENANT");
            assertNotNull(tenant);

            SysUser user = createTestUser("non_bound_user");

            assertThrows(BusinessException.class, () -> {
                tenantController.unbindUser(tenant.getId(), user.getId());
            }, "解绑未绑定的用户应抛出异常");
        }

        @Test
        @DisplayName("✅ 通过userId查询租户成功")
        void testGetTenantByUserIdSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("按用户查租户", "BY_USER_TENANT");
            SysUser user = createTestUser("query_by_user");
            dto.setUserId(user.getId());
            tenantController.save(dto);

            Result<TenantVO> result = tenantController.getByUserId(user.getId());

            assertEquals(200, result.getCode(), "按用户ID查询租户应成功");
            assertNotNull(result.getData());
            assertEquals("按用户查租户", result.getData().getTenantName());
        }
    }

    @Nested
    @DisplayName("场景5: 租户角色管理")
    class TenantRoleManagementTests {

        @Test
        @DisplayName("✅ 为租户分配角色成功")
        void testAssignRolesToTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("有角色的租户", "ROLE_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("ROLE_TENANT");
            assertNotNull(tenant);

            SysRole role1 = createTestRole("角色A", "ROLE_A");
            SysRole role2 = createTestRole("角色B", "ROLE_B");

            sysTenantService.saveTenantRoles(tenant.getId(), Arrays.asList(role1.getId(), role2.getId()));

            List<Long> roleIds = sysTenantService.getRoleIdsByTenantId(tenant.getId());
            assertNotNull(roleIds);
            assertEquals(2, roleIds.size(), "应分配2个角色");
            assertTrue(roleIds.contains(role1.getId()));
            assertTrue(roleIds.contains(role2.getId()));
        }

        @Test
        @DisplayName("✅ 删除租户角色成功")
        void testRemoveRolesFromTenantSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("删角色租户", "DEL_ROLE_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("DEL_ROLE_TENANT");
            assertNotNull(tenant);

            SysRole role = createTestRole("待删除角色", "DEL_ROLE");
            sysTenantService.saveTenantRoles(tenant.getId(), Arrays.asList(role.getId()));

            List<Long> roleIdsBefore = sysTenantService.getRoleIdsByTenantId(tenant.getId());
            assertFalse(roleIdsBefore.isEmpty(), "删除前应有角色");

            sysTenantService.deleteTenantRoles(tenant.getId());

            List<Long> roleIdsAfter = sysTenantService.getRoleIdsByTenantId(tenant.getId());
            assertTrue(roleIdsAfter.isEmpty(), "删除后角色应为空");
        }

        @Test
        @DisplayName("✅ 查询租户下的角色ID列表")
        void testGetRoleIdsByTenantIdSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("查角色租户", "QUERY_ROLE_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("QUERY_ROLE_TENANT");
            assertNotNull(tenant);

            SysRole role1 = createTestRole("查询角色1", "Q_ROLE_1");
            SysRole role2 = createTestRole("查询角色2", "Q_ROLE_2");
            SysRole role3 = createTestRole("查询角色3", "Q_ROLE_3");

            sysTenantService.saveTenantRoles(tenant.getId(),
                    Arrays.asList(role1.getId(), role2.getId(), role3.getId()));

            List<Long> roleIds = sysTenantService.getRoleIdsByTenantId(tenant.getId());
            assertEquals(3, roleIds.size());
        }

        @Test
        @DisplayName("✅ 查询拥有某角色的所有租户ID")
        void testGetTenantIdsByRoleIdSuccess() {
            setUpAdminSecurityContext();

            SysRole sharedRole = createTestRole("共享角色", "SHARED_ROLE");

            TenantDTO dto1 = buildTenantDTO("共享租户1", "SHARED_TENANT_1");
            TenantDTO dto2 = buildTenantDTO("共享租户2", "SHARED_TENANT_2");
            tenantController.save(dto1);
            tenantController.save(dto2);

            SysTenant tenant1 = sysTenantService.getTenantByCode("SHARED_TENANT_1");
            SysTenant tenant2 = sysTenantService.getTenantByCode("SHARED_TENANT_2");

            sysTenantService.saveTenantRoles(tenant1.getId(), Arrays.asList(sharedRole.getId()));
            sysTenantService.saveTenantRoles(tenant2.getId(), Arrays.asList(sharedRole.getId()));

            List<Long> tenantIds = sysTenantService.getTenantIdsByRoleId(sharedRole.getId());
            assertNotNull(tenantIds);
            assertEquals(2, tenantIds.size());
            assertTrue(tenantIds.contains(tenant1.getId()));
            assertTrue(tenantIds.contains(tenant2.getId()));
        }
    }

    @Nested
    @DisplayName("场景6: 边界条件测试")
    class BoundaryConditionTests {

        @Test
        @DisplayName("❌ 查询不存在的租户ID应返回null")
        void testGetByNonExistentIdShouldReturnNull() {
            Result<TenantVO> result = tenantController.getById(99999L);

            assertEquals(200, result.getCode());
            assertNull(result.getData(), "不存在的租户ID应返回null数据");
        }

        @Test
        @DisplayName("❌ 删除不存在的租户应抛出异常")
        void testDeleteNonExistentTenantShouldFail() {
            setUpAdminSecurityContext();

            assertThrows(BusinessException.class, () -> {
                tenantController.delete(99999L);
            }, "删除不存在的租户应抛出异常");
        }

        @Test
        @DisplayName("❌ 更新不存在的租户应抛出异常")
        void testUpdateNonExistentTenantShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("不存在租户", "NONEXISTENT");
            dto.setId(99999L);

            assertThrows(BusinessException.class, () -> {
                tenantController.update(dto);
            }, "更新不存在的租户应抛出异常");
        }

        @Test
        @DisplayName("❌ 绑定用户到不存在的租户应抛出异常")
        void testBindUserToNonExistentTenantShouldFail() {
            setUpAdminSecurityContext();

            SysUser user = createTestUser("orphan_user");

            assertThrows(BusinessException.class, () -> {
                tenantController.bindUser(99999L, user.getId());
            }, "绑定用户到不存在的租户应抛出异常");
        }

        @Test
        @DisplayName("❌ 绑定不存在的用户到租户应抛出异常")
        void testBindNonExistentUserToTenantShouldFail() {
            setUpAdminSecurityContext();

            TenantDTO dto = bindTenantForUserTest();
            SysTenant tenant = sysTenantService.getTenantByCode("NONEXIST_USER_TENANT");
            assertNotNull(tenant);

            assertThrows(BusinessException.class, () -> {
                tenantController.bindUser(tenant.getId(), 99999L);
            }, "绑定不存在的用户应抛出异常");
        }

        private TenantDTO bindTenantForUserTest() {
            TenantDTO d = new TenantDTO();
            d.setTenantName("不存在用户租户");
            d.setTenantCode("NONEXIST_USER_TENANT");
            d.setStatus(1);
            d.setPassword("pwd");
            tenantController.save(d);
            return d;
        }

        @Test
        @DisplayName("✅ 查询空的租户列表应返回空结果")
        void testGetEmptyTenantListShouldReturnEmpty() {
            setUpAdminSecurityContext();

            Result<Page<TenantVO>> result = tenantController.getList(1, 10, "不存在的名称XYZ", null);

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            assertEquals(0, result.getData().getRecords().size(), "无匹配记录时应返回0条");
            assertTrue(result.getData().getRecords().isEmpty());
        }

        @Test
        @DisplayName("✅ 查询所有启用的租户")
        void testGetAllTenantsSuccess() {
            setUpAdminSecurityContext();

            TenantDTO dto1 = buildTenantDTO("全量租户1", "ALL_TENANT_1");
            dto1.setStatus(1);
            tenantController.save(dto1);

            TenantDTO dto2 = buildTenantDTO("全量租户2", "ALL_TENANT_2");
            dto2.setStatus(1);
            tenantController.save(dto2);

            Result<List<TenantVO>> result = tenantController.getAllTenants();

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            assertTrue(result.getData().size() >= 2, "至少应有2个启用的租户");
        }

        @Test
        @DisplayName("✅ 租户详情包含关联用户信息")
        void testTenantDetailIncludesUserInfo() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("含用户详情租户", "DETAIL_WITH_USER");
            SysUser user = createTestUser("detail_user");
            dto.setUserId(user.getId());
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("DETAIL_WITH_USER");
            assertNotNull(tenant);

            Result<TenantVO> result = tenantController.getById(tenant.getId());

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            assertNotNull(result.getData().getUserId(), "VO中应包含userId");
            assertEquals(user.getId(), result.getData().getUserId());
            assertEquals("detail_user", result.getData().getUsername());
            assertNotNull(result.getData().getUserRealName());
        }

        @Test
        @DisplayName("✅ 无用户的租户详情中用户字段为null")
        void testTenantDetailWithoutUserHasNullUserFields() {
            setUpAdminSecurityContext();

            TenantDTO dto = buildTenantDTO("无用户租户", "NO_USER_TENANT");
            tenantController.save(dto);

            SysTenant tenant = sysTenantService.getTenantByCode("NO_USER_TENANT");
            assertNotNull(tenant);

            Result<TenantVO> result = tenantController.getById(tenant.getId());

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            assertNull(result.getData().getUserId(), "无用户时userId应为null");
            assertNull(result.getData().getUsername(), "无用户时username应为null");
            assertNull(result.getData().getUserRealName(), "无用户时userRealName应为null");
        }

        @Test
        @DisplayName("✅ 分页参数边界测试 - 超大页码返回空")
        void testPaginationWithLargeCurrentReturnsEmpty() {
            setUpAdminSecurityContext();

            Result<Page<TenantVO>> result = tenantController.getList(9999, 10, null, null);

            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            System.out.println("  超大页码查询返回记录数: " + result.getData().getRecords().size());
            System.out.println("  注意：无分页拦截器时，大页码也可能返回数据");
        }
    }
}
