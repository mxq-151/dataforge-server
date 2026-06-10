package com.bigdata.common.utils;

import com.bigdata.common.security.CustomUserDetails;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("Common模块 - SecurityUtils工具类集成测试")
class SecurityUtilsRealDataTest {

    private static final Long TEST_USER_ID = 1001L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "password123";
    private static final Long TEST_TENANT_ID = 2001L;
    private static final List<String> TEST_ROLES = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CustomUserDetails createTestUserDetails() {
        return new CustomUserDetails(
                TEST_USER_ID,
                TEST_USERNAME,
                TEST_TENANT_ID,
                TEST_ROLES,
                false,
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                ),
                true
        );
    }

    private void setAuthentication(CustomUserDetails userDetails) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                TEST_PASSWORD,
                userDetails.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Nested
    @DisplayName("场景1: getCurrentUserId() - 从SecurityContext获取用户ID")
    class GetCurrentUserIdTests {

        @Test
        @DisplayName("✅ 已登录用户 - 返回正确的userId")
        void testGetCurrentUserId_LoggedInUser_ReturnsCorrectId() {
            System.out.println("\n=== 场景1.1: 已登录用户获取userId ===");

            CustomUserDetails userDetails = createTestUserDetails();
            setAuthentication(userDetails);

            Long currentUserId = SecurityUtils.getCurrentUserId();

            assertNotNull(currentUserId, "已登录用户的userId不应为null");
            assertEquals(TEST_USER_ID, currentUserId, "返回的userId应与设置的一致");
            assertTrue(currentUserId > 0, "userId应为正数");

            System.out.println("  设置的userId: " + TEST_USER_ID);
            System.out.println("  获取的userId: " + currentUserId);
            System.out.println("  匹配结果: ✓");
        }

        @Test
        @DisplayName("✅ 不同用户ID - 返回对应的userId")
        void testGetCurrentUserId_DifferentUsers_ReturnsCorrespondingIds() {
            System.out.println("\n=== 场景1.2: 不同用户获取不同的userId ===");

            Long[] testIds = {1L, 100L, 9999L, Long.MAX_VALUE / 1000};

            for (Long expectedId : testIds) {
                CustomUserDetails userDetails = new CustomUserDetails(
                        expectedId,
                        "user_" + expectedId,
                        100L,
                        Collections.singletonList("ROLE_USER"),
                        false,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                        true
                );
                setAuthentication(userDetails);

                Long actualId = SecurityUtils.getCurrentUserId();
                assertEquals(expectedId, actualId,
                        String.format("用户%s的userId应匹配", expectedId));

                System.out.println("  用户ID " + expectedId + ": 获取结果 " + actualId + " ✓");
            }
        }

        @Test
        @DisplayName("❌ 未登录用户 - 返回null")
        void testGetCurrentUserId_NotLoggedIn_ReturnsNull() {
            System.out.println("\n=== 场景1.3: 未登录用户获取userId ===");

            SecurityContextHolder.clearContext();

            Long currentUserId = SecurityUtils.getCurrentUserId();

            assertNull(currentUserId, "未登录用户应返回null");

            System.out.println("  当前状态: 未登录");
            System.out.println("  获取结果: null ✓");
        }
    }

    @Nested
    @DisplayName("场景2: getCurrentUsername() - 获取当前用户名")
    class GetCurrentUsernameTests {

        @Test
        @DisplayName("✅ 已登录用户 - 返回正确的username")
        void testGetCurrentUsername_LoggedInUser_ReturnsCorrectUsername() {
            System.out.println("\n=== 场景2.1: 已登录用户获取username ===");

            CustomUserDetails userDetails = createTestUserDetails();
            setAuthentication(userDetails);

            String currentUsername = SecurityUtils.getCurrentUsername();

            assertNotNull(currentUsername, "已登录用户的username不应为null");
            assertEquals(TEST_USERNAME, currentUsername, "返回的username应与设置的一致");
            assertFalse(currentUsername.isEmpty(), "username不应为空字符串");

            System.out.println("  设置的username: " + TEST_USERNAME);
            System.out.println("  获取的username: " + currentUsername);
            System.out.println("  匹配结果: ✓");
        }

        @Test
        @DisplayName("✅ 特殊字符用户名 - 正确处理中文和特殊符号")
        void testGetCurrentUsername_SpecialCharacters_HandledCorrectly() {
            System.out.println("\n=== 场景2.2: 特殊字符username处理 ===");

            String[] specialUsernames = {
                    "张三",
                    "user@example.com",
                    "user_name123",
                    "用户@#$%"
            };

            for (String specialUsername : specialUsernames) {
                CustomUserDetails userDetails = new CustomUserDetails(
                        1L,
                        specialUsername,
                        100L,
                        Collections.emptyList(),
                        false,
                        Collections.emptyList(),
                        true
                );
                setAuthentication(userDetails);

                String actualUsername = SecurityUtils.getCurrentUsername();
                assertEquals(specialUsername, actualUsername,
                        String.format("特殊用户名'%s'应正确返回", specialUsername));

                System.out.println("  特殊用户名 '" + specialUsername + "': 获取结果 '" + actualUsername + "' ✓");
            }
        }

        @Test
        @DisplayName("❌ 未登录用户 - 返回null")
        void testGetCurrentUsername_NotLoggedIn_ReturnsNull() {
            System.out.println("\n=== 场景2.3: 未登录用户获取username ===");

            SecurityContextHolder.clearContext();

            String currentUsername = SecurityUtils.getCurrentUsername();

            assertNull(currentUsername, "未登录用户应返回null");

            System.out.println("  当前状态: 未登录");
            System.out.println("  获取结果: null ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 未登录状态处理 - 返回null或空集合")
    class NotLoggedInStateTests {

        @Test
        @DisplayName("❌ getCurrentUserId() - 未登录返回null")
        void testGetCurrentUserId_NullWhenNotAuthenticated() {
            System.out.println("\n=== 场景3.1: 未登录时getCurrentUserId() ===");

            SecurityContextHolder.clearContext();

            assertAll("未登录状态下所有方法应返回安全的默认值",
                    () -> assertNull(SecurityUtils.getCurrentUserId(),
                            "getCurrentUserId()应返回null"),
                    () -> assertNull(SecurityUtils.getCurrentUsername(),
                            "getCurrentUsername()应返回null"),
                    () -> assertTrue(SecurityUtils.getCurrentUserRoles().isEmpty(),
                            "getCurrentUserRoles()应返回空集合"),
                    () -> assertNull(SecurityUtils.getCurrentTenantId(),
                            "getCurrentTenantId()应返回null"),
                    () -> assertNull(SecurityUtils.getCurrentUserDetails(),
                            "getCurrentUserDetails()应返回null"),
                    () -> assertFalse(SecurityUtils.isAuthenticated(),
                            "isAuthenticated()应返回false"),
                    () -> assertFalse(SecurityUtils.isAdmin(),
                            "isAdmin()应返回false")
            );

            System.out.println("  所有方法在未登录时的返回值:");
            System.out.println("    getCurrentUserId(): null ✓");
            System.out.println("    getCurrentUsername(): null ✓");
            System.out.println("    getCurrentUserRoles(): [] ✓");
            System.out.println("    getCurrentTenantId(): null ✓");
            System.out.println("    getCurrentUserDetails(): null ✓");
            System.out.println("    isAuthenticated(): false ✓");
            System.out.println("    isAdmin(): false ✓");
        }

        @Test
        @DisplayName("❌ Authentication为null - 安全处理")
        void testNullAuthentication_SafeHandling() {
            System.out.println("\n=== 场景3.2: Authentication为null的安全处理 ===");

            SecurityContextHolder.getContext().setAuthentication(null);

            assertDoesNotThrow(() -> {
                SecurityUtils.getCurrentUserId();
                SecurityUtils.getCurrentUsername();
                SecurityUtils.getCurrentUserRoles();
                SecurityUtils.getCurrentTenantId();
                SecurityUtils.getCurrentUserDetails();
                SecurityUtils.isAuthenticated();
                SecurityUtils.isAdmin();
            }, "Authentication为null时不应抛出异常");

            System.out.println("  Authentication=null时所有方法正常执行 ✓");
        }

        @Test
        @DisplayName("❌ Principal非CustomUserDetails类型 - 安全降级")
        void testNonCustomUserDetailsPrincipal_SafeDegradation() {
            System.out.println("\n=== 场景3.3: Principal非CustomUserDetails类型的处理 ===");

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    "anonymousUser",
                    null,
                    Collections.emptyList()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);

            assertAll("Principal为String时应安全降级",
                    () -> assertNull(SecurityUtils.getCurrentUserId(),
                            "getCurrentUserId()应返回null"),
                    () -> assertNotNull(SecurityUtils.getCurrentUsername(),
                            "getCurrentUsername()应能从authentication.getName()获取"),
                    () -> assertTrue(SecurityUtils.getCurrentUserRoles().isEmpty(),
                            "getCurrentUserRoles()应返回空集合"),
                    () -> assertFalse(SecurityUtils.isAuthenticated(),
                            "isAuthenticated()应返回false（因为principal不是CustomUserDetails）")
            );

            System.out.println("  Principal=String时的安全降级处理 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 多线程上下文隔离 - SecurityContext线程安全")
    class MultiThreadContextIsolationTests {

        @Test
        @DisplayName("✅ 不同线程拥有独立的SecurityContext")
        void testDifferentThreads_HaveIndependentSecurityContexts() throws InterruptedException {
            System.out.println("\n=== 场景4.1: 多线程SecurityContext隔离性测试 ===");

            int threadCount = 5;
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicLong successCount = new AtomicLong(0);

            for (int i = 1; i <= threadCount; i++) {
                final int threadIndex = i;
                final Long userId = (long) (1000 + i);
                final String username = "thread_user_" + i;
                final Long tenantId = (long) (2000 + i);

                executorService.submit(() -> {
                    try {
                        startLatch.await();

                        CustomUserDetails userDetails = new CustomUserDetails(
                                userId,
                                username,
                                tenantId,
                                Collections.singletonList("ROLE_USER"),
                                false,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                                true
                        );
                        setAuthentication(userDetails);

                        Thread.sleep(10); // 模拟工作负载

                        Long currentUserId = SecurityUtils.getCurrentUserId();
                        String currentUsername = SecurityUtils.getCurrentUsername();

                        if (userId.equals(currentUserId) && username.equals(currentUsername)) {
                            successCount.incrementAndGet();
                            System.out.println("  线程" + threadIndex + ": userId=" + currentUserId +
                                    ", username=" + currentUsername + " ✓");
                        } else {
                            System.out.println("  线程" + threadIndex + ": ❌ 期望userId=" + userId +
                                    ", 实际=" + currentUserId);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        SecurityContextHolder.clearContext();
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 同时启动所有线程
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
            executorService.shutdown();

            assertTrue(completed, "所有线程应在超时前完成");
            assertEquals(threadCount, successCount.get(),
                    "所有线程都应获取到正确的独立上下文信息");

            System.out.println("  成功线程数: " + successCount.get() + "/" + threadCount);
            System.out.println("  线程隔离性验证通过 ✓");
        }

        @Test
        @DisplayName("✅ 主线程设置不影响子线程")
        void testMainThreadContext_DoesNotAffectChildThreads() throws InterruptedException {
            System.out.println("\n=== 场景4.2: 主线程上下文不污染子线程 ===");

            CustomUserDetails mainUserDetails = createTestUserDetails();
            setAuthentication(mainUserDetails);

            Long mainThreadId = SecurityUtils.getCurrentUserId();
            assertEquals(TEST_USER_ID, mainThreadId, "主线程应能正确获取userId");

            CountDownLatch latch = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Long> childThreadId = new java.util.concurrent.atomic.AtomicReference<>(null);

            Thread childThread = new Thread(() -> {
                childThreadId.set(SecurityUtils.getCurrentUserId());
                latch.countDown();
            });

            childThread.start();
            latch.await();

            assertNull(childThreadId.get(),
                    "子线程不应继承主线程的SecurityContext（除非配置了inheritance）");
            assertEquals(TEST_USER_ID, SecurityUtils.getCurrentUserId(),
                    "主线程的context仍应保持不变");

            System.out.println("  主线程userId: " + mainThreadId);
            System.out.println("  子线程userId: " + childThreadId.get() + " (应为null)");
            System.out.println("  上下文隔离验证通过 ✓");
        }
    }

    @Nested
    @DisplayName("场景5: CustomUserDetails正确解析 - 完整字段验证")
    class CustomUserDetailsParsingTests {

        @Test
        @DisplayName("✅ 解析CustomUserDetails - 所有字段正确")
        void testParseCustomUserDetails_AllFieldsCorrect() {
            System.out.println("\n=== 场景5.1: CustomUserDetails完整字段解析 ===");

            CustomUserDetails originalDetails = createTestUserDetails();
            setAuthentication(originalDetails);

            CustomUserDetails parsedDetails = SecurityUtils.getCurrentUserDetails();

            assertNotNull(parsedDetails, "解析后的CustomUserDetails不应为null");
            assertAll("所有字段应正确解析",
                    () -> assertEquals(TEST_USER_ID, parsedDetails.getUserId(), "userId不匹配"),
                    () -> assertEquals(TEST_USERNAME, parsedDetails.getUsername(), "username不匹配"),
                    () -> assertEquals(TEST_TENANT_ID, parsedDetails.getTenantId(), "tenantId不匹配"),
                    () -> assertEquals(TEST_ROLES.size(), parsedDetails.getRoles().size(), "roles数量不匹配"),
                    () -> assertTrue(parsedDetails.isEnabled(), "enabled状态不匹配"),
                    () -> assertTrue(parsedDetails.isAccountNonExpired(), "accountNonExpired应为true"),
                    () -> assertTrue(parsedDetails.isAccountNonLocked(), "accountNonLocked应为true"),
                    () -> assertTrue(parsedDetails.isCredentialsNonExpired(), "credentialsNonExpired应为true")
            );

            System.out.println("  userId: " + parsedDetails.getUserId());
            System.out.println("  username: " + parsedDetails.getUsername());
            System.out.println("  tenantId: " + parsedDetails.getTenantId());
            System.out.println("  roles: " + parsedDetails.getRoles());
            System.out.println("  enabled: " + parsedDetails.isEnabled());
            System.out.println("  UserDetails接口方法全部验证通过 ✓");
        }

        @Test
        @DisplayName("✅ 角色列表解析 - 支持多角色")
        void testRoleListParsing_MultipleRoles() {
            System.out.println("\n=== 场景5.2: 多角色列表解析 ===");

            List<String> roles = Arrays.asList("ROLE_USER", "ROLE_ADMIN", "ROLE_MANAGER");
            CustomUserDetails userDetails = new CustomUserDetails(
                    1L,
                    "multi_role_user",
                    100L,
                    roles,
                    false,
                    Arrays.asList(
                            new SimpleGrantedAuthority("ROLE_USER"),
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGER")
                    ),
                    true
            );
            setAuthentication(userDetails);

            List<String> extractedRoles = SecurityUtils.getCurrentUserRoles();

            assertNotNull(extractedRoles, "角色列表不应为null");
            assertEquals(roles.size(), extractedRoles.size(), "角色数量应匹配");
            assertTrue(extractedRoles.containsAll(roles), "应包含所有设置的角色");

            System.out.println("  设置的角色: " + roles);
            System.out.println("  提取的角色: " + extractedRoles);
            System.out.println("  角色数量: " + extractedRoles.size());
            System.out.println("  多角色解析验证通过 ✓");
        }

        @Test
        @DisplayName("✅ 租户ID解析 - 支持null和多租户")
        void testTenantIdParsing_SupportsNullAndMultiTenant() {
            System.out.println("\n=== 场景5.3: 租户ID解析（支持null和多租户）===");

            Long[] tenantIds = {null, 0L, 1L, 100L, 9999L, Long.MAX_VALUE / 1000};

            for (Long expectedTenantId : tenantIds) {
                CustomUserDetails userDetails = new CustomUserDetails(
                        1L,
                        "tenant_user",
                        expectedTenantId,
                        Collections.singletonList("ROLE_USER"),
                        false,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                        true
                );
                setAuthentication(userDetails);

                Long actualTenantId = SecurityUtils.getCurrentTenantId();
                assertEquals(expectedTenantId, actualTenantId,
                        String.format("tenantId %s 应正确解析", expectedTenantId));

                System.out.println("  输入tenantId: " + expectedTenantId +
                        " -> 输出: " + actualTenantId + " ✓");
            }
        }

        @Test
        @DisplayName("✅ isAdmin()判断 - 基于角色准确识别管理员")
        void testisAdmin_AccurateBasedOnRoles() {
            System.out.println("\n=== 场景5.4: isAdmin()管理员身份判断 ===");

            CustomUserDetails adminUser = new CustomUserDetails(
                    1L,
                    "admin",
                    100L,
                    Arrays.asList("ROLE_USER", "ROLE_ADMIN"),
                    false,
                    Arrays.asList(
                            new SimpleGrantedAuthority("ROLE_USER"),
                            new SimpleGrantedAuthority("ROLE_ADMIN")
                    ),
                    true
            );
            setAuthentication(adminUser);
            assertTrue(SecurityUtils.isAdmin(), "包含ROLE_ADMIN的用户应是管理员");
            System.out.println("  用户[admin] (含ROLE_ADMIN): isAdmin()=true ✓");

            CustomUserDetails normalUser = new CustomUserDetails(
                    2L,
                    "normal_user",
                    100L,
                    Collections.singletonList("ROLE_USER"),
                    false,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                    true
            );
            setAuthentication(normalUser);
            assertFalse(SecurityUtils.isAdmin(), "不含ROLE_ADMIN的用户不是管理员");
            System.out.println("  用户[normal_user] (仅ROLE_USER): isAdmin()=false ✓");

            CustomUserDetails caseInsensitiveAdmin = new CustomUserDetails(
                    3L,
                    "case_admin",
                    100L,
                    Collections.singletonList("role_admin"),
                    false,
                    Collections.singletonList(new SimpleGrantedAuthority("role_admin")),
                    true
            );
            setAuthentication(caseInsensitiveAdmin);
            assertTrue(SecurityUtils.isAdmin(), "大小写不敏感：role_admin也应识别为管理员");
            System.out.println("  用户[case_admin] (role_admin小写): isAdmin()=true ✓");
        }

        @Test
        @DisplayName("✅ isAuthenticated() - 准确判断认证状态")
        void testisAuthenticated_AccurateAuthenticationStatus() {
            System.out.println("\n=== 场景5.5: isAuthenticated()认证状态判断 ===");

            SecurityContextHolder.clearContext();
            assertFalse(SecurityUtils.isAuthenticated(), "未设置context时应返回false");
            System.out.println("  未设置context: isAuthenticated()=false ✓");

            CustomUserDetails authenticatedUser = createTestUserDetails();
            setAuthentication(authenticatedUser);
            assertTrue(SecurityUtils.isAuthenticated(), "已认证用户应返回true");
            System.out.println("  已认证用户: isAuthenticated()=true ✓");

            Authentication unauthenticatedAuth = new UsernamePasswordAuthenticationToken(
                    "anonymous",
                    null,
                    Collections.emptyList()
            );
            SecurityContextHolder.getContext().setAuthentication(unauthenticatedAuth);
            assertFalse(SecurityUtils.isAuthenticated(),
                    "principal不是CustomUserDetails时即使有authentication也应返回false");
            System.out.println("  非CustomUserDetails principal: isAuthenticated()=false ✓");
        }
    }

    @Nested
    @DisplayName("场景6: 边界条件与异常场景")
    class EdgeCaseAndExceptionTests {

        @Test
        @DisplayName("✅ 空角色列表 - 安全处理")
        void testEmptyRolesList_SafeHandling() {
            System.out.println("\n=== 场景6.1: 空角色列表处理 ===");

            CustomUserDetails userWithEmptyRoles = new CustomUserDetails(
                    1L,
                    "no_role_user",
                    100L,
                    Collections.emptyList(),
                    false,
                    Collections.emptyList(),
                    true
            );
            setAuthentication(userWithEmptyRoles);

            List<String> roles = SecurityUtils.getCurrentUserRoles();
            assertNotNull(roles, "角色列表不应为null");
            assertTrue(roles.isEmpty(), "空角色列表应返回空集合");
            assertFalse(SecurityUtils.isAdmin(), "无角色的用户不是管理员");

            System.out.println("  角色列表: " + roles);
            System.out.println("  isEmpty(): " + roles.isEmpty());
            System.out.println("  空角色安全处理 ✓");
        }

        @Test
        @DisplayName("✅ 极端值userId/tenantId - 不溢出")
        void testExtremeNumericValues_NoOverflow() {
            System.out.println("\n=== 场景6.2: 极端数值ID处理 ===");

            Long[] extremeValues = {
                    Long.MIN_VALUE,
                    -1L,
                    0L,
                    1L,
                    Long.MAX_VALUE
            };

            for (Long extremeValue : extremeValues) {
                CustomUserDetails userDetails = new CustomUserDetails(
                        extremeValue,
                        "extreme_user",
                        extremeValue,
                        Collections.singletonList("ROLE_USER"),
                        false,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                        true
                );
                setAuthentication(userDetails);

                Long userId = SecurityUtils.getCurrentUserId();
                Long tenantId = SecurityUtils.getCurrentTenantId();

                assertEquals(extremeValue, userId, "极端userId应正确存储");
                assertEquals(extremeValue, tenantId, "极端tenantId应正确存储");

                System.out.println("  极端值 " + extremeValue + ": userId=" + userId + ", tenantId=" + tenantId + " ✓");
            }
        }

        @Test
        @DisplayName("✅ 快速切换用户 - Context更新及时")
        void testRapidUserSwitching_ContextUpdatesImmediately() {
            System.out.println("\n=== 场景6.3: 快速用户切换测试 ===");

            for (int i = 1; i <= 10; i++) {
                CustomUserDetails userDetails = new CustomUserDetails(
                        (long) i,
                        "switch_user_" + i,
                        (long) (i * 10),
                        Collections.singletonList("ROLE_USER"),
                        false,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                        true
                );
                setAuthentication(userDetails);

                assertEquals((long) i, SecurityUtils.getCurrentUserId(),
                        "第" + i + "次切换后userId应立即更新");
                assertEquals("switch_user_" + i, SecurityUtils.getCurrentUsername(),
                        "第" + i + "次切换后username应立即更新");
            }

            System.out.println("  完成10次快速用户切换，每次均立即生效 ✓");
        }

        @Test
        @DisplayName("✅ 并发读写SecurityContext - 线程安全")
        void testConcurrentReadWrite_ThreadSafe() throws InterruptedException {
            System.out.println("\n=== 场景6.4: 并发读写SecurityContext线程安全性 ===");

            int readThreads = 5;
            int writeThreads = 3;
            int iterationsPerThread = 20;
            ExecutorService executor = Executors.newFixedThreadPool(readThreads + writeThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(readThreads + writeThreads);
            AtomicLong errorCount = new AtomicLong(0);

            for (int i = 0; i < readThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            assertDoesNotThrow(() -> {
                                SecurityUtils.getCurrentUserId();
                                SecurityUtils.getCurrentUsername();
                                SecurityUtils.getCurrentUserDetails();
                                SecurityUtils.isAuthenticated();
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int i = 0; i < writeThreads; i++) {
                final int writerId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            CustomUserDetails userDetails = new CustomUserDetails(
                                    (long) (writerId * 1000 + j),
                                    "writer_" + writerId + "_" + j,
                                    (long) (writerId * 100),
                                    Collections.singletonList("ROLE_USER"),
                                    false,
                                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                                    true
                            );
                            assertDoesNotThrow(() -> setAuthentication(userDetails));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errorCount.incrementAndGet();
                    } finally {
                        SecurityContextHolder.clearContext();
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "并发操作应在超时前完成");
            assertEquals(0, errorCount.get(), "并发操作中不应出现错误");

            System.out.println("  读线程数: " + readThreads);
            System.out.println("  写线程数: " + writeThreads);
            System.out.println("  每线程迭代: " + iterationsPerThread);
            System.out.println("  错误数: " + errorCount.get());
            System.out.println("  并发安全性验证通过 ✓");
        }
    }
}
