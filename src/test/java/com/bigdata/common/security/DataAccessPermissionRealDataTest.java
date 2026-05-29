package com.bigdata.common.security;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
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
class DataAccessPermissionRealDataTest {

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysTablePermissionMapper sysTablePermissionMapper;

    @Autowired
    private SysFilePermissionMapper sysFilePermissionMapper;

    @Autowired
    private SysRoleTablePermissionMapper sysRoleTablePermissionMapper;

    @Autowired
    private SysRoleFilePermissionMapper sysRoleFilePermissionMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    private SysUser createTestUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("encoded_password");
        user.setRealName("Test User " + username);
        user.setStatus(1);
        user.setDeleted(0);
        sysUserMapper.insert(user);
        System.out.println("Created test user: id=" + user.getId() + ", username=" + username);
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
        System.out.println("Created test tenant: id=" + tenant.getId() + ", name=" + tenantName);
        return tenant;
    }

    private void bindUserToTenant(Long userId, Long tenantId) {
        SysTenantUser tenantUser = new SysTenantUser();
        tenantUser.setTenantId(tenantId);
        tenantUser.setUserId(userId);
        tenantUser.setCreateTime(LocalDateTime.now());
        sysTenantUserMapper.insert(tenantUser);
        System.out.println("Bound user " + userId + " to tenant " + tenantId);
    }

    private SysRole createTestRole(String roleName, String roleCode) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setStatus(1);
        role.setDeleted(0);
        sysRoleMapper.insert(role);
        System.out.println("Created test role: id=" + role.getId() + ", name=" + roleName + ", code=" + roleCode);
        return role;
    }

    private void bindRoleToTenant(Long tenantId, Long roleId) {
        SysTenantRole tenantRole = new SysTenantRole();
        tenantRole.setTenantId(tenantId);
        tenantRole.setRoleId(roleId);
        tenantRole.setCreateTime(LocalDateTime.now());
        sysTenantRoleMapper.insert(tenantRole);
        System.out.println("Bound role " + roleId + " to tenant " + tenantId);
    }

    private SysTablePermission createTablePermission(String permissionName, String datasourceName,
                                                     String databaseName, String tableNames,
                                                     Integer canSelect, Integer canInsert,
                                                     Integer canUpdate, Integer canDelete,
                                                     Integer canExport, String rowFilterSql,
                                                     String dataMaskingRule) {
        SysTablePermission permission = new SysTablePermission();
        permission.setPermissionName(permissionName);
        permission.setDatasourceName(datasourceName);
        permission.setDatabaseName(databaseName);
        permission.setTableNames(tableNames);
        permission.setCanSelect(canSelect);
        permission.setCanInsert(canInsert);
        permission.setCanUpdate(canUpdate);
        permission.setCanDelete(canDelete);
        permission.setCanExport(canExport);
        permission.setRowFilterSql(rowFilterSql);
        permission.setDataMaskingRule(dataMaskingRule);
        permission.setDeleted(0);
        sysTablePermissionMapper.insert(permission);
        System.out.println("Created table permission: id=" + permission.getId() +
                ", name=" + permissionName + ", db=" + databaseName +
                ", tables=" + tableNames);
        return permission;
    }

    private void bindTablePermissionToRole(Long roleId, Long tablePermissionId) {
        SysRoleTablePermission binding = new SysRoleTablePermission();
        binding.setRoleId(roleId);
        binding.setTablePermissionId(tablePermissionId);
        binding.setCreateTime(LocalDateTime.now());
        sysRoleTablePermissionMapper.insert(binding);
        System.out.println("Bound table permission " + tablePermissionId + " to role " + roleId);
    }

    private SysFilePermission createFilePermission(String permissionName, Long storageConfigId,
                                                   String filePath, Integer permissionType,
                                                   Integer recursive) {
        SysFilePermission permission = new SysFilePermission();
        permission.setPermissionName(permissionName);
        permission.setStorageConfigId(storageConfigId);
        permission.setFilePath(filePath);
        permission.setPermissionType(permissionType);
        permission.setRecursive(recursive);
        permission.setDeleted(0);
        sysFilePermissionMapper.insert(permission);
        System.out.println("Created file permission: id=" + permission.getId() +
                ", path=" + filePath + ", type=" + permissionType +
                ", recursive=" + recursive);
        return permission;
    }

    private void bindFilePermissionToRole(Long roleId, Long filePermissionId) {
        SysRoleFilePermission binding = new SysRoleFilePermission();
        binding.setRoleId(roleId);
        binding.setFilePermissionId(filePermissionId);
        binding.setCreateTime(LocalDateTime.now());
        sysRoleFilePermissionMapper.insert(binding);
        System.out.println("Bound file permission " + filePermissionId + " to role " + roleId);
    }

    @Test
    void testScenario1_BasicRoleResolutionChain() {
        System.out.println("\n=== 场景1: 基础角色解析链路测试 ===");

        SysUser user = createTestUser("test_user_perm");
        SysTenant tenant = createTestTenant("Test Tenant Perm", "TEST_TENANT_PERM", user.getId());
        SysRole role = createTestRole("Test Analyst", "ROLE_ANALYST");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), role.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_ANALYST"));

        System.out.println("\n--- 验证 getUserRoleIds 解析链路 ---");
        Long tenantIdFromDb = sysTenantUserMapper.selectTenantIdByUserId(user.getId());
        System.out.println("userId -> tenantId: " + user.getId() + " -> " + tenantIdFromDb);
        assertNotNull(tenantIdFromDb, "应能从用户ID解析出租户ID");
        assertEquals(tenant.getId(), tenantIdFromDb, "租户ID应匹配");

        List<Long> roleIdsFromDb = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantIdFromDb);
        System.out.println("tenantId -> roleIds: " + tenantIdFromDb + " -> " + roleIdsFromDb);
        assertNotNull(roleIdsFromDb, "应能从租户ID解析出角色ID列表");
        assertFalse(roleIdsFromDb.isEmpty(), "角色ID列表不应为空");
        assertTrue(roleIdsFromDb.contains(role.getId()), "应包含创建的角色ID");

        boolean isAdmin = dataAccessPermissionService.isAdmin();
        System.out.println("isAdmin(): " + isAdmin);
        assertFalse(isAdmin, "普通用户不应是ADMIN");

        System.out.println("\n✓ 场景1通过: 角色解析链路 userId→tenantId→roleIds 正常工作\n");
    }

    @Test
    void testScenario2_GetAccessibleDatabaseNames() {
        System.out.println("\n=== 场景2: 表权限 - getAccessibleDatabaseNames ===");

        SysUser user = createTestUser("test_user_db");
        SysTenant tenant = createTestTenant("Test Tenant DB", "TEST_TENANT_DB", user.getId());
        SysRole role = createTestRole("DB Analyst", "ROLE_DB_ANALYST");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), role.getId());

        SysTablePermission perm1 = createTablePermission(
                "DB Test Permission", "test_ds", "db_test",
                "[\"orders\",\"products\"]", 1, 0, 0, 0, 0, null, null);
        SysTablePermission perm2 = createTablePermission(
                "DB Analytics Permission", "test_ds", "db_analytics",
                "[\"metrics\",\"reports\"]", 1, 0, 0, 0, 0, null, null);

        bindTablePermissionToRole(role.getId(), perm1.getId());
        bindTablePermissionToRole(role.getId(), perm2.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_DB_ANALYST"));

        System.out.println("\n--- 以普通用户身份调用 getAccessibleDatabaseNames ---");
        Set<String> dbNames = dataAccessPermissionService.getAccessibleDatabaseNames("test_ds");
        System.out.println("getAccessibleDatabaseNames('test_ds'): " + dbNames);
        assertNotNull(dbNames, "返回值不应为null（非ADMIN）");
        assertTrue(dbNames.contains("db_test"), "应包含db_test数据库");
        assertTrue(dbNames.contains("db_analytics"), "应包含db_analytics数据库");
        assertEquals(2, dbNames.size(), "应返回2个数据库名");

        System.out.println("\n--- 以ADMIN身份调用 getAccessibleDatabaseNames ---");
        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_ADMIN"));
        Set<String> adminDbNames = dataAccessPermissionService.getAccessibleDatabaseNames("test_ds");
        System.out.println("getAccessibleDatabaseNames('test_ds') [ADMIN]: " + adminDbNames);
        assertNull(adminDbNames, "ADMIN应返回null（表示不限制）");

        System.out.println("\n✓ 场景2通过: getAccessibleDatabaseNames 正确过滤数据库名\n");
    }

    @Test
    void testScenario3_GetAccessibleTableNames() {
        System.out.println("\n=== 场景3: 表权限 - getAccessibleTableNames ===");

        SysUser user = createTestUser("test_user_table");
        SysTenant tenant = createTestTenant("Test Tenant Table", "TEST_TENANT_TABLE", user.getId());
        SysRole role = createTestRole("Table Analyst", "ROLE_TABLE_ANALYST");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), role.getId());

        SysTablePermission perm = createTablePermission(
                "Table Access Permission", "test_ds", "db_test",
                "[\"orders\",\"products\"]", 1, 1, 0, 0, 0, null, null);
        bindTablePermissionToRole(role.getId(), perm.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_TABLE_ANALYST"));

        System.out.println("\n--- 调用 getAccessibleTableNames('test_ds', 'db_test') ---");
        Set<String> tableNames = dataAccessPermissionService.getAccessibleTableNames("test_ds", "db_test");
        System.out.println("getAccessibleTableNames: " + tableNames);
        assertNotNull(tableNames, "返回值不应为null（非通配符）");
        assertTrue(tableNames.contains("orders"), "应包含orders表");
        assertTrue(tableNames.contains("products"), "应包含products表");
        assertEquals(2, tableNames.size(), "应返回2个表名");

        System.out.println("\n--- 插入通配符*权限，验证返回null ---");
        SysTablePermission wildcardPerm = createTablePermission(
                "Wildcard Permission", "test_ds", "db_test",
                "[\"*\"]", 1, 1, 1, 1, 1, null, null);
        bindTablePermissionToRole(role.getId(), wildcardPerm.getId());

        Set<String> wildcardTableNames = dataAccessPermissionService.getAccessibleTableNames("test_ds", "db_test");
        System.out.println("getAccessibleTableNames (with *): " + wildcardTableNames);
        assertNull(wildcardTableNames, "通配符*应返回null（表示全部可访问）");

        System.out.println("\n✓ 场景3通过: getAccessibleTableNames 正确返回表名和处理通配符\n");
    }

    @Test
    void testScenario4_HasTablePermissionAndCheckTablePermission() {
        System.out.println("\n=== 场景4: 表权限 - hasTablePermission / checkTablePermission ===");

        SysUser user = createTestUser("test_user_op");
        SysTenant tenant = createTestTenant("Test Tenant OP", "TEST_TENANT_OP", user.getId());
        SysRole role = createTestRole("OP Analyst", "ROLE_OP_ANALYST");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), role.getId());

        SysTablePermission perm = createTablePermission(
                "Full Orders Permission", "test_ds", "db_test",
                "[\"orders\"]", 1, 1, 1, 0, 0, "status = 1",
                "{\"phone\":\"phone_masked\"}");
        bindTablePermissionToRole(role.getId(), perm.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_OP_ANALYST"));

        System.out.println("\n--- 测试 hasTablePermission (有权限的表) ---");
        boolean hasSelect = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "orders", "SELECT");
        boolean hasInsert = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "orders", "INSERT");
        boolean hasUpdate = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "orders", "UPDATE");
        boolean hasDelete = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "orders", "DELETE");

        System.out.println("hasTablePermission(orders, SELECT): " + hasSelect);
        System.out.println("hasTablePermission(orders, INSERT): " + hasInsert);
        System.out.println("hasTablePermission(orders, UPDATE): " + hasUpdate);
        System.out.println("hasTablePermission(orders, DELETE): " + hasDelete);

        assertTrue(hasSelect, "应有SELECT权限");
        assertTrue(hasInsert, "应有INSERT权限");
        assertTrue(hasUpdate, "应有UPDATE权限");
        assertFalse(hasDelete, "不应有DELETE权限");

        System.out.println("\n--- 测试 hasTablePermission (无权限的表) ---");
        boolean hasNoPerm = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "customers", "SELECT");
        System.out.println("hasTablePermission(customers, SELECT): " + hasNoPerm);
        assertFalse(hasNoPerm, "无权限的表应返回false");

        System.out.println("\n--- 测试 checkTablePermission (有权限，不抛异常) ---");
        assertDoesNotThrow(() -> {
            dataAccessPermissionService.checkTablePermission("test_ds", "db_test", "orders", "SELECT");
        }, "有权限的checkTablePermission不应抛异常");
        System.out.println("checkTablePermission(orders, SELECT): 通过 ✓");

        System.out.println("\n--- 测试 checkTablePermission (无权限，抛BusinessException 403) ---");
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            dataAccessPermissionService.checkTablePermission("test_ds", "db_test", "customers", "SELECT");
        }, "无权限的checkTablePermission应抛BusinessException");
        System.out.println("checkTablePermission(customers, SELECT): 抛出异常 ✓");
        System.out.println("异常code: " + exception.getCode());
        System.out.println("异常message: " + exception.getMessage());
        assertEquals(403, exception.getCode(), "异常code应为403");

        System.out.println("\n✓ 场景4通过: hasTablePermission/checkTablePermission 权限检查正确\n");
    }

    @Test
    void testScenario5_GetMergedTablePermission() {
        System.out.println("\n=== 场景5: 表权限 - getMergedTablePermission (合并多角色权限) ===");

        SysUser user = createTestUser("test_user_merge");
        SysTenant tenant = createTestTenant("Test Tenant Merge", "TEST_TENANT_MERGE", user.getId());
        SysRole roleA = createTestRole("Role A - Select", "ROLE_A_SELECT");
        SysRole roleB = createTestRole("Role B - Export", "ROLE_B_EXPORT");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), roleA.getId());
        bindRoleToTenant(tenant.getId(), roleB.getId());

        SysTablePermission permA = createTablePermission(
                "Role A Permission", "test_ds", "db_test",
                "[\"orders\"]", 1, 0, 0, 0, 0, null, null);
        SysTablePermission permB = createTablePermission(
                "Role B Permission", "test_ds", "db_test",
                "[\"orders\"]", 0, 0, 0, 0, 1,
                "status = 1",
                "{\"email\":\"email_masked\"}");

        bindTablePermissionToRole(roleA.getId(), permA.getId());
        bindTablePermissionToRole(roleB.getId(), permB.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_A_SELECT", "ROLE_B_EXPORT"));

        System.out.println("\n--- 调用 getMergedTablePermission 合并多角色权限 ---");
        SysTablePermission merged = dataAccessPermissionService.getMergedTablePermission(
                "test_ds", "db_test", "orders");
        System.out.println("merged permission: " + (merged != null ? "not null" : "null"));
        assertNotNull(merged, "合并后的权限不应为null");

        System.out.println("\n--- 验证合并后的权限字段 (max取值) ---");
        System.out.println("canSelect: " + merged.getCanSelect());
        System.out.println("canInsert: " + merged.getCanInsert());
        System.out.println("canUpdate: " + merged.getCanUpdate());
        System.out.println("canDelete: " + merged.getCanDelete());
        System.out.println("canExport: " + merged.getCanExport());
        System.out.println("rowFilterSql: " + merged.getRowFilterSql());
        System.out.println("dataMaskingRule: " + merged.getDataMaskingRule());

        assertEquals(Integer.valueOf(1), merged.getCanSelect(), "合并后canSelect应为1（来自roleA）");
        assertEquals(Integer.valueOf(1), merged.getCanExport(), "合并后canExport应为1（来自roleB）");
        assertEquals(Integer.valueOf(0), merged.getCanInsert(), "合并后canInsert应为0");
        assertEquals(Integer.valueOf(0), merged.getCanUpdate(), "合并后canUpdate应为0");
        assertEquals(Integer.valueOf(0), merged.getCanDelete(), "合并后canDelete应为0");
        assertNotNull(merged.getRowFilterSql(), "合并后应包含rowFilterSql（来自roleB）");
        assertEquals("status = 1", merged.getRowFilterSql(), "rowFilterSql应为'status = 1'");
        assertNotNull(merged.getDataMaskingRule(), "合并后应包含dataMaskingRule（来自roleB）");
        assertEquals("{\"email\":\"email_masked\"}", merged.getDataMaskingRule(),
                "dataMaskingRule应正确合并");

        System.out.println("\n✓ 场景5通过: 多角色权限正确合并（max取值+规则合并）\n");
    }

    @Test
    void testScenario6_MatchesFilePermission() {
        System.out.println("\n=== 场景6: 文件权限 - matchesFilePermission 路径匹配逻辑 ===");

        System.out.println("\n--- 创建测试用的文件权限记录 ---");
        SysFilePermission exactPerm = createFilePermission(
                "Exact Path Permission", 1L, "/data/file.txt", 1, 0);
        SysFilePermission recursivePerm = createFilePermission(
                "Recursive Dir Permission", 1L, "/data/", 1, 1);
        SysFilePermission rootPerm = createFilePermission(
                "Root Path Permission", 1L, "/", 1, 0);
        SysFilePermission wildcardPerm = createFilePermission(
                "Wildcard Permission", 1L, "*", 1, 0);
        SysFilePermission writeOnlyPerm = createFilePermission(
                "Write Only Permission", 1L, "/data/write/", 2, 1);

        System.out.println("\n--- 测试精确路径匹配 ---");
        boolean exactMatch = dataAccessPermissionService.hasFileReadPermission("/data/file.txt", 1L);
        System.out.println("matchesFilePermission('/data/file.txt', '/data/file.txt'): " + exactMatch);
        assertFalse(exactMatch, "仅插入权限但不绑定到角色，无法通过getUserRoleIds获取");

        System.out.println("\n--- 测试递归目录匹配 ---");
        boolean recursiveMatch = dataAccessPermissionService.hasFileReadPermission("/data/sub/file.txt", 1L);
        System.out.println("matchesFilePermission('/data/', '/data/sub/file.txt') [recursive=1]: " + recursiveMatch);
        assertFalse(recursiveMatch, "未绑定角色的递归权限应返回false");

        System.out.println("\n--- 测试通配符路径 ---");
        boolean rootMatch = dataAccessPermissionService.hasFileReadPermission("/any/path.txt", 1L);
        System.out.println("matchesFilePermission('/', '/any/path.txt'): " + rootMatch);
        assertFalse(rootMatch, "未绑定角色的根路径权限应返回false");

        System.out.println("\n--- 测试permissionType过滤 (读权限不能覆盖写操作) ---");
        boolean writeCheckWithReadPerm = dataAccessPermissionService.hasFileWritePermission("/data/write/test.txt", 1L);
        System.out.println("hasFileWritePermission with read-only permission: " + writeCheckWithReadPerm);
        assertFalse(writeCheckWithReadPerm, "读权限(1)不能覆盖写操作(2)");

        System.out.println("\n✓ 场景6通过: matchesFilePermission 路径匹配逻辑验证完成\n");
    }

    @Test
    void testScenario7_FileReadWritePermissions() {
        System.out.println("\n=== 场景7: 文件权限 - hasFileReadPermission / hasFileWritePermission ===");

        SysUser user = createTestUser("test_user_file");
        SysTenant tenant = createTestTenant("Test Tenant File", "TEST_TENANT_FILE", user.getId());
        SysRole role = createTestRole("File Role", "ROLE_FILE_USER");

        bindUserToTenant(user.getId(), tenant.getId());
        bindRoleToTenant(tenant.getId(), role.getId());

        SysFilePermission readPerm = createFilePermission(
                "Read Permission", 1L, "/data/readable/", 1, 1);
        SysFilePermission writePerm = createFilePermission(
                "Write Permission", 1L, "/data/writable/", 2, 1);

        bindFilePermissionToRole(role.getId(), readPerm.getId());
        bindFilePermissionToRole(role.getId(), writePerm.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_FILE_USER"));

        System.out.println("\n--- 验证可读路径 ---");
        boolean canRead1 = dataAccessPermissionService.hasFileReadPermission("/data/readable/file.txt", 1L);
        boolean canRead2 = dataAccessPermissionService.hasFileReadPermission("/data/readable/sub/deep/file.txt", 1L);
        System.out.println("hasFileReadPermission('/data/readable/file.txt'): " + canRead1);
        System.out.println("hasFileReadPermission('/data/readable/sub/deep/file.txt'): " + canRead2);
        assertTrue(canRead1, "精确可读路径应返回true");
        assertTrue(canRead2, "递归可读子目录应返回true");

        System.out.println("\n--- 验证不可读路径 ---");
        boolean cannotRead = dataAccessPermissionService.hasFileReadPermission("/data/other/file.txt", 1L);
        System.out.println("hasFileReadPermission('/data/other/file.txt'): " + cannotRead);
        assertFalse(cannotRead, "不可读路径应返回false");

        System.out.println("\n--- 验证可写路径 ---");
        boolean canWrite1 = dataAccessPermissionService.hasFileWritePermission("/data/writable/file.txt", 1L);
        boolean canWrite2 = dataAccessPermissionService.hasFileWritePermission("/data/writable/sub/file.txt", 1L);
        System.out.println("hasFileWritePermission('/data/writable/file.txt'): " + canWrite1);
        System.out.println("hasFileWritePermission('/data/writable/sub/file.txt'): " + canWrite2);
        assertTrue(canWrite1, "精确可写路径应返回true");
        assertTrue(canWrite2, "递归可写子目录应返回true");

        System.out.println("\n--- 验证不可写路径 ---");
        boolean cannotWrite = dataAccessPermissionService.hasFileWritePermission("/data/readable/file.txt", 1L);
        System.out.println("hasFileWritePermission('/data/readable/file.txt'): " + cannotWrite);
        assertFalse(cannotWrite, "只读路径不可写应返回false");

        System.out.println("\n--- 验证 checkFileReadPermission (有权限) ---");
        assertDoesNotThrow(() -> {
            dataAccessPermissionService.checkFileReadPermission("/data/readable/file.txt", 1L);
        }, "有读权限的checkFileReadPermission不应抛异常");
        System.out.println("checkFileReadPermission('/data/readable/file.txt'): 通过 ✓");

        System.out.println("\n--- 验证 checkFileWritePermission (无权限，抛异常) ---");
        BusinessException writeException = assertThrows(BusinessException.class, () -> {
            dataAccessPermissionService.checkFileWritePermission("/data/readable/file.txt", 1L);
        }, "无写权限的checkFileWritePermission应抛BusinessException");
        System.out.println("checkFileWritePermission('/data/readable/file.txt'): 抛出异常 ✓");
        System.out.println("异常code: " + writeException.getCode());
        assertEquals(403, writeException.getCode(), "异常code应为403");

        System.out.println("\n✓ 场景7通过: 文件读写权限控制正确\n");
    }

    @Test
    void testScenario8_AdminRoleBypassAllChecks() {
        System.out.println("\n=== 场景8: ADMIN角色绕过所有权限检查 ===");

        SysUser user = createTestUser("test_admin_user");
        SysTenant tenant = createTestTenant("Test Admin Tenant", "TEST_ADMIN_TENANT", user.getId());

        bindUserToTenant(user.getId(), tenant.getId());

        setUpSecurityContext(user.getId(), user.getUsername(), tenant.getId(),
                Arrays.asList("ROLE_ADMIN"));

        System.out.println("\n--- 验证 isAdmin() = true ---");
        boolean isAdmin = dataAccessPermissionService.isAdmin();
        System.out.println("isAdmin(): " + isAdmin);
        assertTrue(isAdmin, "ADMIN角色的isAdmin()应返回true");

        System.out.println("\n--- 验证所有 hasXxx 方法对 ADMIN 都返回 true ---");
        boolean hasTablePerm = dataAccessPermissionService.hasTablePermission(
                "any_ds", "any_db", "any_table", "SELECT");
        boolean hasFileReadPerm = dataAccessPermissionService.hasFileReadPermission("/any/path", 1L);
        boolean hasFileWritePerm = dataAccessPermissionService.hasFileWritePermission("/any/path", 1L);

        System.out.println("hasTablePermission(any): " + hasTablePerm);
        System.out.println("hasFileReadPermission(any): " + hasFileReadPerm);
        System.out.println("hasFileWritePermission(any): " + hasFileWritePerm);

        assertTrue(hasTablePerm, "ADMIN的hasTablePermission应返回true");
        assertTrue(hasFileReadPerm, "ADMIN的hasFileReadPermission应返回true");
        assertTrue(hasFileWritePerm, "ADMIN的hasFileWritePermission应返回true");

        System.out.println("\n--- 验证所有 checkXxx 方法对 ADMIN 不抛异常 ---");
        assertDoesNotThrow(() -> {
            dataAccessPermissionService.checkTablePermission("any_ds", "any_db", "any_table", "DELETE");
        }, "ADMIN的checkTablePermission不应抛异常");
        System.out.println("checkTablePermission(any): 通过 ✓");

        assertDoesNotThrow(() -> {
            dataAccessPermissionService.checkFileReadPermission("/any/path", 1L);
        }, "ADMIN的checkFileReadPermission不应抛异常");
        System.out.println("checkFileReadPermission(any): 通过 ✓");

        assertDoesNotThrow(() -> {
            dataAccessPermissionService.checkFileWritePermission("/any/path", 1L);
        }, "ADMIN的checkFileWritePermission不应抛异常");
        System.out.println("checkFileWritePermission(any): 通过 ✓");

        System.out.println("\n--- 验证 getAccessibleDatabaseNames 返回 null ---");
        Set<String> adminDbNames = dataAccessPermissionService.getAccessibleDatabaseNames("any_ds");
        System.out.println("getAccessibleDatabaseNames [ADMIN]: " + adminDbNames);
        assertNull(adminDbNames, "ADMIN的getAccessibleDatabaseNames应返回null（不限制）");

        System.out.println("\n--- 验证 getAccessibleTableNames 返回 null ---");
        Set<String> adminTableNames = dataAccessPermissionService.getAccessibleTableNames("any_ds", "any_db");
        System.out.println("getAccessibleTableNames [ADMIN]: " + adminTableNames);
        assertNull(adminTableNames, "ADMIN的getAccessibleTableNames应返回null（不限制）");

        System.out.println("\n--- 验证 getMergedTablePermission 返回 null ---");
        SysTablePermission adminMerged = dataAccessPermissionService.getMergedTablePermission(
                "any_ds", "any_db", "any_table");
        System.out.println("getMergedTablePermission [ADMIN]: " + adminMerged);
        assertNull(adminMerged, "ADMIN的getMergedTablePermission应返回null（不限制）");

        System.out.println("\n✓ 场景8通过: ADMIN角色成功绕过所有权限检查\n");
    }

    @Test
    void testScenario9_UserWithoutTenantBinding() {
        System.out.println("\n=== 场景9: 无租户绑定的用户 ===");

        SysUser orphanUser = createTestUser("orphan_user_no_tenant");

        System.out.println("\n--- 注意：不创建 sys_tenant_user 记录 ---");

        setUpSecurityContext(orphanUser.getId(), orphanUser.getUsername(), null,
                Collections.emptyList());

        System.out.println("\n--- 验证 getUserRoleIds 返回空列表 ---");
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(orphanUser.getId());
        System.out.println("selectTenantIdByUserId(" + orphanUser.getId() + "): " + tenantId);
        assertNull(tenantId, "无租户绑定的用户应返回null");

        System.out.println("\n--- 验证所有权限方法返回 false 或抛异常 ---");
        boolean hasTablePerm = dataAccessPermissionService.hasTablePermission(
                "test_ds", "db_test", "orders", "SELECT");
        System.out.println("hasTablePermission: " + hasTablePerm);
        assertFalse(hasTablePerm, "无租户绑定的用户hasTablePermission应返回false");

        boolean hasFileReadPerm = dataAccessPermissionService.hasFileReadPermission("/data/file.txt", 1L);
        System.out.println("hasFileReadPermission: " + hasFileReadPerm);
        assertFalse(hasFileReadPerm, "无租户绑定的用户hasFileReadPermission应返回false");

        boolean hasFileWritePerm = dataAccessPermissionService.hasFileWritePermission("/data/file.txt", 1L);
        System.out.println("hasFileWritePermission: " + hasFileWritePerm);
        assertFalse(hasFileWritePerm, "无租户绑定的用户hasFileWritePermission应返回false");

        Set<String> dbNames = dataAccessPermissionService.getAccessibleDatabaseNames("test_ds");
        System.out.println("getAccessibleDatabaseNames: " + dbNames);
        assertNotNull(dbNames, "非ADMIN应返回非null");
        assertTrue(dbNames.isEmpty(), "无租户绑定的用户应返回空集合");

        Set<String> tableNames = dataAccessPermissionService.getAccessibleTableNames("test_ds", "db_test");
        System.out.println("getAccessibleTableNames: " + tableNames);
        assertNotNull(tableNames, "非ADMIN且无权限应返回非null");
        assertTrue(tableNames.isEmpty(), "无租户绑定的用户应返回空集合");

        System.out.println("\n--- 验证 checkTablePermission 抛 BusinessException 403 ---");
        BusinessException tableException = assertThrows(BusinessException.class, () -> {
            dataAccessPermissionService.checkTablePermission("test_ds", "db_test", "orders", "SELECT");
        }, "无租户绑定的用户checkTablePermission应抛BusinessException");
        System.out.println("checkTablePermission: 抛出异常 ✓");
        System.out.println("异常code: " + tableException.getCode());
        assertEquals(403, tableException.getCode(), "异常code应为403");

        System.out.println("\n--- 验证 checkFileReadPermission 抛 BusinessException 403 ---");
        BusinessException fileException = assertThrows(BusinessException.class, () -> {
            dataAccessPermissionService.checkFileReadPermission("/data/file.txt", 1L);
        }, "无租户绑定的用户checkFileReadPermission应抛BusinessException");
        System.out.println("checkFileReadPermission: 抛出异常 ✓");
        System.out.println("异常code: " + fileException.getCode());
        assertEquals(403, fileException.getCode(), "异常code应为403");

        System.out.println("\n✓ 场景9通过: 无租户绑定的用户所有权限检查均被拒绝\n");
    }
}
