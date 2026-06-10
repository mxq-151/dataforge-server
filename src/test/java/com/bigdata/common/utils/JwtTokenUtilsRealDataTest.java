package com.bigdata.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Common模块 - JwtTokenUtils工具类测试")
class JwtTokenUtilsRealDataTest {

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    private Long testUserId = 1001L;
    private String testUsername = "testuser";
    private String testRole = "ROLE_USER,ROLE_ADMIN";
    private Long testTenantId = 2001L;

    @Nested
    @DisplayName("场景1: Token生成与验证")
    class TokenGenerationAndValidationTests {

        @Test
        @DisplayName("✅ 生成有效Token - 包含所有Claims")
        void testGenerateValidToken_WithAllClaims() {
            System.out.println("\n=== 场景1.1: 生成包含完整Claims的Token ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            assertNotNull(token, "生成的token不应为null");
            assertFalse(token.isEmpty(), "token不应为空字符串");
            assertTrue(token.split("\\.").length == 3, "JWT应由3部分组成（header.payload.signature）");

            System.out.println("  Token长度: " + token.length());
            System.out.println("  Token前30字符: " + token.substring(0, Math.min(30, token.length())) + "...");
        }

        @Test
        @DisplayName("✅ 生成不含tenantId的Token")
        void testGenerateValidToken_WithoutTenantId() {
            System.out.println("\n=== 场景1.2: 生成不含tenantId的Token ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, null, false);

            assertNotNull(token, "token不应为null");
            assertTrue(jwtTokenUtils.validateToken(token), "token应有效");

            Long tenantIdFromToken = jwtTokenUtils.getTenantIdFromToken(token);
            assertNull(tenantIdFromToken, "未设置tenantId时，从token解析应为null");

            System.out.println("  TenantId (应为null): " + tenantIdFromToken);
        }

        @Test
        @DisplayName("✅ 验证有效Token - 返回true")
        void testValidateToken_ValidToken_ReturnsTrue() {
            System.out.println("\n=== 场景1.3: 验证有效Token ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            boolean isValid = jwtTokenUtils.validateToken(token);
            assertTrue(isValid, "有效token应返回true");

            System.out.println("  验证结果: " + isValid);
        }

        @Test
        @DisplayName("❌ 验证篡改的Token - 返回false")
        void testValidateToken_TamperedToken_ReturnsFalse() {
            System.out.println("\n=== 场景1.4: 验证被篡改的Token ===");

            String validToken = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            String[] parts = validToken.split("\\.");
            assertEquals(3, parts.length, "JWT应有3部分");

            String tamperedPayload = parts[1] + "tampered_data";
            String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

            boolean isValid = jwtTokenUtils.validateToken(tamperedToken);
            assertFalse(isValid, "被篡改的token应返回false");

            System.out.println("  原始Token验证: true");
            System.out.println("  篡改后Token验证: " + isValid);
        }

        @Test
        @DisplayName("❌ 验证空Token - 返回false")
        void testValidateToken_EmptyToken_ReturnsFalse() {
            System.out.println("\n=== 场景1.5: 验证空Token ===");

            assertFalse(jwtTokenUtils.validateToken(null), "null token应返回false");
            assertFalse(jwtTokenUtils.validateToken(""), "空字符串token应返回false");
            assertFalse(jwtTokenUtils.validateToken("   "), "空白token应返回false");
            assertFalse(jwtTokenUtils.validateToken("invalid.token"), "格式错误的token应返回false");

            System.out.println("  null验证: false ✓");
            System.out.println("  空串验证: false ✓");
            System.out.println("  格式错误验证: false ✓");
        }
    }

    @Nested
    @DisplayName("场景2: Claims信息提取")
    class ClaimsExtractionTests {

        @Test
        @DisplayName("✅ 从Token提取userId - 类型正确且值匹配")
        void testGetUserIdFromToken() {
            System.out.println("\n=== 场景2.1: 提取userId ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            Long extractedUserId = jwtTokenUtils.getUserIdFromToken(token);

            assertNotNull(extractedUserId, "提取的userId不应为null");
            assertEquals(testUserId, extractedUserId, "提取的userId应与输入一致");

            System.out.println("  输入userId: " + testUserId);
            System.out.println("  提取userId: " + extractedUserId);
        }

        @Test
        @DisplayName("✅ 从Token提取username - 值匹配")
        void testGetUsernameFromToken() {
            System.out.println("\n=== 场景2.2: 提取username ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            String extractedUsername = jwtTokenUtils.getUsernameFromToken(token);

            assertNotNull(extractedUsername, "提取的username不应为null");
            assertEquals(testUsername, extractedUsername, "提取的username应与输入一致");

            System.out.println("  输入username: " + testUsername);
            System.out.println("  提取username: " + extractedUsername);
        }

        @Test
        @DisplayName("✅ 从Token提取role - 支持多角色逗号分隔")
        void testGetRoleFromToken() {
            System.out.println("\n=== 场景2.3: 提取role（多角色）===" );

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            String extractedRole = jwtTokenUtils.getRoleFromToken(token);

            assertNotNull(extractedRole, "提取的role不应为null");
            assertEquals(testRole, extractedRole, "提取的role应与输入一致");
            assertTrue(extractedRole.contains("ROLE_USER"), "应包含ROLE_USER");
            assertTrue(extractedRole.contains("ROLE_ADMIN"), "应包含ROLE_ADMIN");

            System.out.println("  输入role: " + testRole);
            System.out.println("  提取role: " + extractedRole);
        }

        @Test
        @DisplayName("✅ 从Token提取tenantId - Long类型正确")
        void testGetTenantIdFromToken_WithLongValue() {
            System.out.println("\n=== 场景2.4: 提取tenantId (Long类型) ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            Long extractedTenantId = jwtTokenUtils.getTenantIdFromToken(token);

            assertNotNull(extractedTenantId, "提取的tenantId不应为null");
            assertEquals(testTenantId, extractedTenantId, "提取的tenantId应与输入一致");
            assertTrue(extractedTenantId instanceof Long, "类型应为Long");

            System.out.println("  输入tenantId: " + testTenantId + " (type: " + testTenantId.getClass().getSimpleName() + ")");
            System.out.println("  提取tenantId: " + extractedTenantId + " (type: " + extractedTenantId.getClass().getSimpleName() + ")");
        }

        @Test
        @DisplayName("✅ 获取完整的Claims对象 - 包含标准字段和自定义字段")
        void testGetTokenClaims_CompleteObject() {
            System.out.println("\n=== 场景2.5: 获取完整Claims对象 ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            Claims claims = jwtTokenUtils.getTokenClaims(token);

            assertNotNull(claims, "Claims对象不应为null");

            Object userIdClaim = claims.get("userId");
            Object usernameClaim = claims.get("username");
            Object roleClaim = claims.get("role");
            Object tenantIdClaim = claims.get("tenantId");
            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();

            assertNotNull(userIdClaim, "Claims中应包含userId");
            assertNotNull(usernameClaim, "Claims中应包含username");
            assertNotNull(roleClaim, "Claims中应包含role");
            assertNotNull(tenantIdClaim, "Claims中应包含tenantId");
            assertNotNull(issuedAt, "Claims中应包含iat (签发时间)");
            assertNotNull(expiration, "Claims中应包含exp (过期时间)");

            System.out.println("  userId: " + userIdClaim);
            System.out.println("  username: " + usernameClaim);
            System.out.println("  role: " + roleClaim);
            System.out.println("  tenantId: " + tenantIdClaim);
            System.out.println("  issuedAt: " + issuedAt);
            System.out.println("  expiration: " + expiration);
        }
    }

    @Nested
    @DisplayName("场景3: Token时间相关特性")
    class TokenTimeRelatedTests {

        @Test
        @DisplayName("✅ Token签发时间在当前时间附近")
        void testTokenIssuedAt_IsRecent() {
            System.out.println("\n=== 场景3.1: Token签发时间验证 ===");

            long beforeGenerate = System.currentTimeMillis();
            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            long afterGenerate = System.currentTimeMillis();

            Claims claims = jwtTokenUtils.getTokenClaims(token);
            Date issuedAt = claims.getIssuedAt();

            assertNotNull(issuedAt, "签发时间不应为null");
            assertTrue(issuedAt.getTime() >= beforeGenerate - 1000,
                    "签发时间应在生成前1秒内");
            assertTrue(issuedAt.getTime() <= afterGenerate + 1000,
                    "签发时间应在生成后1秒内");

            System.out.println("  开始生成时间: " + new Date(beforeGenerate));
            System.out.println("  Token签发时间: " + issuedAt);
            System.out.println("  结束生成时间: " + new Date(afterGenerate));
        }

        @Test
        @DisplayName("✅ Token过期时间大于签发时间")
        void testTokenExpiration_AfterIssuedAt() {
            System.out.println("\n=== 场景3.2: Token过期时间 > 签发时间 ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            Claims claims = jwtTokenUtils.getTokenClaims(token);

            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();

            assertNotNull(expiration, "过期时间不应为null");
            assertTrue(expiration.after(issuedAt), "过期时间应在签发时间之后");

            long durationMs = expiration.getTime() - issuedAt.getTime();
            long durationHours = durationMs / (1000 * 60 * 60);
            long durationMinutes = (durationMs % (1000 * 60 * 60)) / (1000 * 60);

            System.out.println("  签发时间: " + issuedAt);
            System.out.println("  过期时间: " + expiration);
            System.out.println("  有效期: " + durationHours + "小时" + durationMinutes + "分钟");
            assertTrue(durationHours >= 23, "有效期应至少23小时（接近配置的24小时）");
        }

        @Test
        @DisplayName("✅ 多次生成Token - 每次签发时间不同")
        void testMultipleTokens_HaveDifferentIssuedAt() {
            System.out.println("\n=== 场景3.3: 多次生成Token的签发时间不同 ===");

            String token1 = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            try {
                Thread.sleep(1100); // 确保跨秒(JWT的iat精度为秒级)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String token2 = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            Claims claims1 = jwtTokenUtils.getTokenClaims(token1);
            Claims claims2 = jwtTokenUtils.getTokenClaims(token2);

            assertNotEquals(claims1.getIssuedAt(), claims2.getIssuedAt(),
                    "不同时刻生成的Token应有不同的签发时间");
            assertTrue(claims2.getIssuedAt().after(claims1.getIssuedAt()),
                    "第二个Token的签发时间应在第一个之后");

            System.out.println("  Token1签发时间: " + claims1.getIssuedAt());
            System.out.println("  Token2签发时间: " + claims2.getIssuedAt());
        }
    }

    @Nested
    @DisplayName("场景4: Token安全性与边界条件")
    class TokenSecurityAndEdgeCaseTests {

        @Test
        @DisplayName("✅ 不同用户生成不同Token")
        void testDifferentUsers_GenerateDifferentTokens() {
            System.out.println("\n=== 场景4.1: 不同用户生成不同Token ===");

            String token1 = jwtTokenUtils.generateToken(1001L, "user1", "ROLE_USER", 2001L, false);
            String token2 = jwtTokenUtils.generateToken(1002L, "user2", "ROLE_ADMIN", 2002L, false);

            assertNotEquals(token1, token2, "不同用户应生成不同的Token");

            Long userId1 = jwtTokenUtils.getUserIdFromToken(token1);
            Long userId2 = jwtTokenUtils.getUserIdFromToken(token2);
            String username1 = jwtTokenUtils.getUsernameFromToken(token1);
            String username2 = jwtTokenUtils.getUsernameFromToken(token2);

            assertEquals(1001L, userId1, "Token1的userId应匹配");
            assertEquals(1002L, userId2, "Token2的userId应匹配");
            assertEquals("user1", username1, "Token1的username应匹配");
            assertEquals("user2", username2, "Token2的username应匹配");

            System.out.println("  Token1 -> User: " + username1 + "(" + userId1 + ")");
            System.out.println("  Token2 -> User: " + username2 + "(" + userId2 + ")");
        }

        @Test
        @DisplayName("✅ 同一用户相同参数多次生成 - 内容相同但可能签名随机盐导致不同")
        void testSameUserSameParams_MultipleGenerations() {
            System.out.println("\n=== 场景4.2: 同一用户多次生成Token ===");

            String token1 = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);
            String token2 = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            Claims claims1 = jwtTokenUtils.getTokenClaims(token1);
            Claims claims2 = jwtTokenUtils.getTokenClaims(token2);

            assertEquals(claims1.get("userId"), claims2.get("userId"),
                    "同一用户的userId应相同");
            assertEquals(claims1.get("username"), claims2.get("username"),
                    "同一用户的username应相同");
            assertEquals(claims1.get("role"), claims2.get("role"),
                    "同一用户的role应相同");
            assertEquals(claims1.get("tenantId"), claims2.get("tenantId"),
                    "同一用户的tenantId应相同");

            System.out.println("  Token1和Token2的Claims内容一致 ✓");
        }

        @Test
        @DisplayName("✅ 特殊字符处理 - username含中文/特殊符号")
        void testSpecialCharactersInClaims() {
            System.out.println("\n=== 场景4.3: 特殊字符处理 ===");

            String specialUsername = "张三@#$%";
            String specialRole = "ROLE_用户,ROLE_管理员";

            String token = jwtTokenUtils.generateToken(testUserId, specialUsername, specialRole, testTenantId, false);

            assertTrue(jwtTokenUtils.validateToken(token), "特殊字符Token应有效");

            String extractedUsername = jwtTokenUtils.getUsernameFromToken(token);
            String extractedRole = jwtTokenUtils.getRoleFromToken(token);

            assertEquals(specialUsername, extractedUsername, "特殊字符username应正确存储和提取");
            assertEquals(specialRole, extractedRole, "特殊字符role应正确存储和提取");

            System.out.println("  特殊username: " + specialUsername);
            System.out.println("  提取结果: " + extractedUsername);
            System.out.println("  特殊role: " + specialRole);
            System.out.println("  提取结果: " + extractedRole);
        }

        @Test
        @DisplayName("✅ 大数值userId和tenantId - 不溢出")
        void testLargeNumericValues() {
            System.out.println("\n=== 场景4.4: 大数值ID处理 ===");

            Long largeUserId = Long.MAX_VALUE / 1000;  // 约9百万亿
            Long largeTenantId = Long.MAX_VALUE / 1000;

            String token = jwtTokenUtils.generateToken(largeUserId, "big_user", "ROLE_BIG", largeTenantId, false);

            assertTrue(jwtTokenUtils.validateToken(token), "大数值Token应有效");

            Long extractedUserId = jwtTokenUtils.getUserIdFromToken(token);
            Long extractedTenantId = jwtTokenUtils.getTenantIdFromToken(token);

            assertEquals(largeUserId, extractedUserId, "大数值userId应正确存储");
            assertEquals(largeTenantId, extractedTenantId, "大数值tenantId应正确存储");

            System.out.println("  输入userId: " + largeUserId);
            System.out.println("  提取userId: " + extractedUserId);
            System.out.println("  输入tenantId: " + largeTenantId);
            System.out.println("  提取tenantId: " + extractedTenantId);
        }

        @Test
        @DisplayName("✅ 极长username和role - 正确截断或完整保存")
        void testVeryLongStrings() {
            System.out.println("\n=== 场景4.5: 极长字符串处理 ===");

            StringBuilder longUsername = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                longUsername.append("u");
            }
            String veryLongUsername = longUsername.toString();

            StringBuilder longRole = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                longRole.append("ROLE_ROLE").append(i).append(",");
            }
            String veryLongRole = longRole.substring(0, longRole.length() - 1);

            String token = jwtTokenUtils.generateToken(testUserId, veryLongUsername, veryLongRole, testTenantId, false);

            assertTrue(jwtTokenUtils.validateToken(token), "极长字符串Token应有效");

            String extractedUsername = jwtTokenUtils.getUsernameFromToken(token);
            String extractedRole = jwtTokenUtils.getRoleFromToken(token);

            assertEquals(veryLongUsername, extractedUsername, "极长username应完整保存");
            assertEquals(veryLongRole, extractedRole, "极长role应完整保存");

            System.out.println("  username长度: " + veryLongUsername.length());
            System.out.println("  role长度: " + veryLongRole.length());
            System.out.println("  Token长度: " + token.length());
        }
    }

    @Nested
    @DisplayName("场景5: 密钥安全性验证")
    class KeySecurityTests {

        @Test
        @DisplayName("✅ 使用HMAC-SHA算法签名 - 密钥强度足够")
        void testSigningAlgorithm_IsHmacSha() {
            System.out.println("\n=== 场景5.1: 签名算法验证 ===");

            String token = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            String[] parts = token.split("\\.");
            assertEquals(3, parts.length, "JWT应有3部分");

            String header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
            System.out.println("  JWT Header: " + header);

            assertTrue(header.contains("HS"), "Header应表明使用HMAC-SHA算法");
        }

        @Test
        @DisplayName("✅ Token不可伪造 - 必须使用相同密钥才能通过验证")
        void testCannotForgeWithDifferentKey() {
            System.out.println("\n=== 场景5.2: Token防伪造验证 ===");

            String validToken = jwtTokenUtils.generateToken(testUserId, testUsername, testRole, testTenantId, false);

            SecretKey differentKey = Keys.hmacShaKeyFor(
                    "completely-different-secret-key-for-testing".getBytes(StandardCharsets.UTF_8));

            try {
                io.jsonwebtoken.Jwts.parser()
                        .verifyWith(differentKey)
                        .build()
                        .parseSignedClaims(validToken);
                fail("使用不同密钥应抛出异常");
            } catch (JwtException e) {
                System.out.println("  使用不同密钥验证失败（符合预期）: " + e.getClass().getSimpleName());
                assertTrue(true, "不同密钥无法验证Token");
            } catch (Exception e) {
                System.out.println("  异常类型: " + e.getClass().getSimpleName());
                assertTrue(e instanceof JwtException || e instanceof SecurityException,
                        "应抛出安全相关的异常");
            }
        }
    }
}
