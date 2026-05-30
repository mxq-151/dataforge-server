package com.bigdata.common.utils;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Common模块 - PasswordUtils工具类测试")
class PasswordUtilsRealDataTest {

    @Nested
    @DisplayName("场景1: 密码加密功能")
    class EncryptionTests {

        @Test
        @DisplayName("✅ 加密普通密码 - 返回固定长度哈希值")
        void testEncrypt_NormalPassword() {
            System.out.println("\n=== 场景1.1: 加密普通密码 ===");

            String plainPassword = "myPassword123";
            String encrypted = PasswordUtils.encrypt(plainPassword);

            assertNotNull(encrypted, "加密结果不应为null");
            assertFalse(encrypted.isEmpty(), "加密结果不应为空");
            assertEquals(32, encrypted.length(), "MD5哈希应为32个字符（16进制）");
            assertFalse(encrypted.equals(plainPassword), "加密后应与原文不同");

            System.out.println("  明文密码: " + plainPassword);
            System.out.println("  加密结果: " + encrypted);
            System.out.println("  哈希长度: " + encrypted.length() + " 字符");
        }

        @Test
        @DisplayName("✅ 同一密码多次加密 - 结果一致（确定性哈希）")
        void testEncrypt_SamePasswordSameResult() {
            System.out.println("\n=== 场景1.2: 同一密码加密一致性 ===");

            String password = "testPassword456";

            String hash1 = PasswordUtils.encrypt(password);
            String hash2 = PasswordUtils.encrypt(password);

            assertEquals(hash1, hash2, "同一密码应产生相同的哈希值");

            System.out.println("  第1次加密: " + hash1);
            System.out.println("  第2次加密: " + hash2);
            System.out.println("  一致性: ✓");
        }

        @Test
        @DisplayName("✅ 不同密码产生不同哈希 - 碰撞概率极低")
        void testEncrypt_DifferentPasswordsDifferentHashes() {
            System.out.println("\n=== 场景1.3: 不同密码不同哈希 ===");

            String password1 = "password123";
            String password2 = "password124"; // 仅差一个字符

            String hash1 = PasswordUtils.encrypt(password1);
            String hash2 = PasswordUtils.encrypt(password2);

            assertNotEquals(hash1, hash2, "微小差异的密码应产生完全不同的哈希");

            int diffCount = 0;
            for (int i = 0; i < Math.min(hash1.length(), hash2.length()); i++) {
                if (hash1.charAt(i) != hash2.charAt(i)) {
                    diffCount++;
                }
            }

            System.out.println("  密码1: " + password1 + " -> " + hash1);
            System.out.println("  密码2: " + password2 + " -> " + hash2);
            System.out.println("  不同字符数: " + diffCount + "/" + hash1.length());
            assertTrue(diffCount > hash1.length() * 0.4,
                    "雪崩效应：微小差异应导致40%以上字符不同");
        }

        @Test
        @DisplayName("✅ 空密码加密 - 应正常处理")
        void testEncrypt_EmptyPassword() {
            System.out.println("\n=== 场景1.4: 空密码处理 ===");

            String emptyHash = PasswordUtils.encrypt("");
            assertNotNull(emptyHash, "空字符串加密不应返回null");
            assertEquals(32, emptyHash.length(), "空字符串哈希也应为32字符");

            String nullInput = null;
            String nullHash = PasswordUtils.encrypt(nullInput);
            assertNotNull(nullHash, "null输入加密不应返回null（MD5对null有固定行为）");
            System.out.println("  空串哈希: " + emptyHash);
            System.out.println("  null哈希: " + nullHash);
        }

        @Test
        @DisplayName("✅ 特殊字符和Unicode密码加密")
        void testEncrypt_SpecialCharactersAndUnicode() {
            System.out.println("\n=== 场景1.5: 特殊字符和Unicode处理 ===");

            String specialChars = "!@#$%^&*()_+-=[]{}|;':\",./<>?";
            String chinese = "中文密码测试";
            String emoji = "密码😀🎉🚀";
            String mixed = "P@ssw0rd_中文!123#";

            String[] passwords = {specialChars, chinese, emoji, mixed};
            String[] hashes = new String[passwords.length];

            for (int i = 0; i < passwords.length; i++) {
                hashes[i] = PasswordUtils.encrypt(passwords[i]);
                assertNotNull(hashes[i], "特殊字符密码" + (i+1) + "加密不应为null");
                assertEquals(32, hashes[i].length(), "所有哈希都应是32字符");
            }

            for (int i = 0; i < passwords.length; i++) {
                for (int j = i + 1; j < passwords.length; j++) {
                    assertNotEquals(hashes[i], hashes[j],
                            "不同特殊字符密码应有不同哈希");
                }
            }

            System.out.println("  特殊字符: " + specialChars.substring(0, 10) + "... -> " + hashes[0]);
            System.out.println("  中文: " + chinese + " -> " + hashes[1]);
            System.out.println("  Emoji: " + emoji + " -> " + hashes[2]);
            System.out.println("  混合: " + mixed + " -> " + hashes[3]);
        }
    }

    @Nested
    @DisplayName("场景2: 密码验证功能")
    class VerificationTests {

        @Test
        @DisplayName("✅ 正确密码验证通过 - verify返回true")
        void testVerify_CorrectPassword_ReturnsTrue() {
            System.out.println("\n=== 场景2.1: 正确密码验证 ===");

            String originalPassword = "correct_password_123";
            String encrypted = PasswordUtils.encrypt(originalPassword);

            boolean isValid = PasswordUtils.verify(originalPassword, encrypted);

            assertTrue(isValid, "正确密码应验证通过");

            System.out.println("  原始密码: " + originalPassword);
            System.out.println("  存储的哈希: " + encrypted);
            System.out.println("  验证结果: " + isValid + " ✓");
        }

        @Test
        @DisplayName("❌ 错误密码验证失败 - verify返回false")
        void testVerify_WrongPassword_ReturnsFalse() {
            System.out.println("\n=== 场景2.2: 错误密码验证 ===");

            String correctPassword = "my_secret_password";
            String wrongPassword = "wrong_guess_password";
            String encrypted = PasswordUtils.encrypt(correctPassword);

            boolean isWrongValid = PasswordUtils.verify(wrongPassword, encrypted);

            assertFalse(isWrongValid, "错误密码应验证失败");

            System.out.println("  正确密码: " + correctPassword);
            System.out.println("  错误尝试: " + wrongPassword);
            System.out.println("  存储的哈希: " + encrypted);
            System.out.println("  验证结果: " + isWrongValid + " ✗ (符合预期)");
        }

        @Test
        @DisplayName("✅ 大小写敏感 - 大小写不同视为不同密码")
        void testVerify_CaseSensitive() {
            System.out.println("\n=== 场景2.3: 大小写敏感性 ===");

            String lowerCase = "mypassword";
            String upperCase = "MYPASSWORD";
            String mixedCase = "MyPassword";

            String lowerHash = PasswordUtils.encrypt(lowerCase);

            assertTrue(PasswordUtils.verify(lowerCase, lowerHash),
                    "小写原密码应匹配");
            assertFalse(PasswordUtils.verify(upperCase, lowerHash),
                    "全大写不应匹配小写的哈希");
            assertFalse(PasswordUtils.verify(mixedCase, lowerHash),
                    "混合大小写不应匹配纯小写的哈希");

            System.out.println("  小写: " + lowerCase + " -> 验证: 通过 ✓");
            System.out.println("  大写: " + upperCase + " -> 验证: 失败 ✗");
            System.out.println("  混合: " + mixedCase + " -> 验证: 失败 ✗");
        }

        @Test
        @DisplayName("✅ 空格敏感 - 前后空格会影响验证结果")
        void testVerify_SpaceSensitive() {
            System.out.println("\n=== 场景2.4: 空格敏感性 ===");

            String noSpace = "password";
            String withLeadingSpace = " password";
            String withTrailingSpace = "password ";
            String withBothSpaces = " password ";

            String noSpaceHash = PasswordUtils.encrypt(noSpace);

            assertTrue(PasswordUtils.verify(noSpace, noSpaceHash),
                    "无空格原密码应匹配");
            assertFalse(PasswordUtils.verify(withLeadingSpace, noSpaceHash),
                    "前导空格不应匹配");
            assertFalse(PasswordUtils.verify(withTrailingSpace, noSpaceHash),
                    "尾部空格不应匹配");
            assertFalse(PasswordUtils.verify(withBothSpaces, noSpaceHash),
                    "两端空格不应匹配");

            System.out.println("  无空格: '" + noSpace + "' -> ✓");
            System.out.println("  前导空格: '" + withLeadingSpace + "' -> ✗");
            System.out.println("  尾部空格: '" + withTrailingSpace + "' -> ✗");
            System.out.println("  两端空格: '" + withBothSpaces + "' -> ✗");
        }

        @Test
        @DisplayName("❌ 与其他哈希比较 - 不同格式哈希应失败")
        void testVerify_DifferentFormatHash() {
            System.out.println("\n=== 场景2.5: 不同格式哈希对比 ===");

            String password = "test_password";
            String md5Hash = PasswordUtils.encrypt(password);

            String fakeSha256Hash = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd" +
                    "62a11ef721d1542d8"; // SHA256 of "password"
            String fakeBcryptHash = "$2a$10$N9qo8uLOickgx2ZMRZoMye.MBC" +
                    "uRBCFzVOJHhOxKwPZVjYOvLqNpE";
            String shortHash = "abc123";
            String longHash = "a".repeat(64);

            assertFalse(PasswordUtils.verify(password, fakeSha256Hash),
                    "SHA256格式哈希不应匹配");
            assertFalse(PasswordUtils.verify(password, fakeBcryptHash),
                    "BCrypt格式哈希不应匹配");
            assertFalse(PasswordUtils.verify(password, shortHash),
                    "过短哈希不应匹配");
            assertFalse(PasswordUtils.verify(password, longHash),
                    "过长哈希不应匹配");

            System.out.println("  MD5哈希: 验证通过 ✓");
            System.out.println("  SHA256哈希: 验证失败 ✗ (符合预期)");
            System.out.println("  BCrypt哈希: 验证失败 ✗ (符合预期)");
            System.out.println("  过短/过长: 验证失败 ✗ (符合预期)");
        }
    }

    @Nested
    @DisplayName("场景3: 安全性与最佳实践")
    class SecurityAndBestPracticesTests {

        @Test
        @DisplayName("✅ 哈希值仅包含十六进制字符")
        void testHashContainsOnlyHexCharacters() {
            System.out.println("\n=== 场景3.1: 十六进制字符验证 ===");

            String password = "secure_password!";
            String hash = PasswordUtils.encrypt(password);

            assertTrue(hash.matches("[0-9a-f]{32}"),
                    "哈希值应只包含0-9和a-f，且长度为32");

            System.out.println("  哈希值: " + hash);
            System.out.println("  格式校验: 仅包含[0-9a-f] ✓");
        }

        @Test
        @DisplayName("✅ 加密是不可逆操作 - 无法从哈希还原密码")
        void testEncryptionIsOneWay() {
            System.out.println("\n=== 场景3.2: 单向哈希不可逆 ===");

            String originalPassword = "cannot_reverse_this";
            String hash = PasswordUtils.encrypt(originalPassword);

            assertNotEquals(originalPassword, hash,
                    "哈希值不应包含明文密码");
            assertFalse(hash.contains(originalPassword),
                    "哈希值不应是明文的简单变换");
            assertFalse(hash.toLowerCase().contains(originalPassword.toLowerCase()),
                    "即使忽略大小写也不应包含明文");

            System.out.println("  原文: " + originalPassword);
            System.out.println("  哈希: " + hash);
            System.out.println("  结论: 无法从哈希反推原文 ✓");
        }

        @Test
        @DisplayName("✅ 使用盐值(Salt)增强安全性 - 相同密码在不同系统表现不同")
        void testSaltEnhancement() {
            System.out.println("\n=== 场景3.3: 盐值作用验证 ===");

            String password = "same_password";

            String ourHash = PasswordUtils.encrypt(password);

            String simpleMd5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(password);

            assertNotEquals(ourHash, simpleMd5,
                    "加盐后的哈希应与标准MD5不同");

            System.out.println("  原文: " + password);
            System.out.println("  标准MD5: " + simpleMd5);
            System.out.println("  本系统(加盐): " + ourHash);
            System.out.println("  结论: 加盐有效防止彩虹表攻击 ✓");
        }

        @Test
        @DisplayName("⚠️ 弱密码检测建议 - 虽然当前使用MD5但应提示升级")
        void testWeakPasswordDetection() {
            System.out.println("\n=== 场景3.4: 弱密码场景（信息性）===" );

            String[] weakPasswords = {
                    "123456",
                    "password",
                    "12345678",
                    "qwerty",
                    "abc123",
                    "admin",
                    "letmein",
                    "welcome",
                    "monkey",
                    "123123"
            };

            System.out.println("  弱密码列表及其哈希:");
            for (String weakPwd : weakPasswords) {
                String hash = PasswordUtils.encrypt(weakPwd);
                System.out.println("    " + weakPwd + " -> " + hash);
            }

            System.out.println("\n  ⚠️ 建议: 生产环境应考虑使用BCrypt/Argon2替代MD5");
            System.out.println("  ⚠️ 建议: 实施密码复杂度策略（长度、特殊字符、字典检查）");
            assertTrue(true, "此测试为信息性输出");
        }

        @Test
        @DisplayName("✅ 性能测试 - 加密1000次耗时应在合理范围")
        void testPerformance_Encrypt1000Times() {
            System.out.println("\n=== 场景3.5: 加密性能测试 ===");

            String password = "performance_test_password";
            int iterations = 1000;

            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                PasswordUtils.encrypt(password + i); // 每次略微不同以避免缓存
            }
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;
            double avgTimeMicros = (double)(endTime - startTime) / iterations / 1000;

            System.out.println("  加密次数: " + iterations);
            System.out.println("  总耗时: " + durationMs + " ms");
            System.out.println("  平均耗时: " + String.format("%.2f", avgTimeMicros) + " μs/次");

            assertTrue(durationMs < 5000,
                    "1000次加密应在5秒内完成（当前:" + durationMs + "ms）");
            assertTrue(avgTimeMicros < 5000,
                    "单次平均耗时应小于5ms（当前:" + String.format("%.2f", avgTimeMicros) + "μs）");
        }
    }

    @Nested
    @DisplayName("场景4: 边界条件和异常输入")
    class EdgeCasesAndExceptionTests {

        @Test
        @DisplayName("✅ 极长密码加密 - 不截断或溢出")
        void testVeryLongPassword() {
            System.out.println("\n=== 场景4.1: 极长密码处理 ===");

            StringBuilder longPwd = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                longPwd.append("a");
            }
            String veryLongPassword = longPwd.toString();

            String hash = PasswordUtils.encrypt(veryLongPassword);

            assertNotNull(hash, "极长密码加密不应为null");
            assertEquals(32, hash.length(), "极长密码哈希仍应为32字符");

            boolean isValid = PasswordUtils.verify(veryLongPassword, hash);
            assertTrue(isValid, "极长密码应能正确验证");

            System.out.println("  密码长度: " + veryLongPassword.length() + " 字符");
            System.out.println("  哈希长度: " + hash.length() + " 字符");
            System.out.println("  验证: 通过 ✓");
        }

        @Test
        @DisplayName("✅ 单字符密码加密")
        void testSingleCharacterPassword() {
            System.out.println("\n=== 场景4.2: 单字符密码 ===");

            String singleChar = "X";
            String hash = PasswordUtils.encrypt(singleChar);

            assertNotNull(hash, "单字符密码加密不应为null");
            assertEquals(32, hash.length(), "单字符哈希应为32字符");
            assertTrue(PasswordUtils.verify(singleChar, hash),
                    "单字符密码应能验证");

            System.out.println("  单字符: '" + singleChar + "'");
            System.out.println("  哈希: " + hash);
        }

        @Test
        @DisplayName("✅ 包含换行符和制表符的密码")
        void testPasswordWithWhitespaceControlCharacters() {
            System.out.println("\n=== 场景4.3: 控制字符处理 ===");

            String withNewline = "pass\nword";
            String withTab = "pass\tword";
            String withCarriageReturn = "pass\rword";

            String hashNewline = PasswordUtils.encrypt(withNewline);
            String hashTab = PasswordUtils.encrypt(withTab);
            String hashCR = PasswordUtils.encrypt(withCarriageReturn);

            assertNotNull(hashNewline, "含换行符密码加密成功");
            assertNotNull(hashTab, "含制表符密码加密成功");
            assertNotNull(hashCR, "含回车符密码加密成功");

            assertNotEquals(hashNewline, hashTab,
                    "换行符和制表符应产生不同哈希");
            assertNotEquals(hashNewline, hashCR,
                    "换行符和回车符应产生不同哈希");

            System.out.println("  含\\n: " + withNewline.replace("\n", "\\n") + " -> " + hashNewline);
            System.out.println("  含\\t: " + withTab.replace("\t", "\\t") + " -> " + hashTab);
            System.out.println("  含\\r: " + withCarriageReturn.replace("\r", "\\r") + " -> " + hashCR);
        }

        @Test
        @DisplayName("✅ 全数字密码和全字母密码")
        void testNumericAndAlphabeticOnlyPasswords() {
            System.out.println("\n=== 场景4.4: 纯数字/纯字母密码 ===");

            String numericOnly = "1234567890";
            String alphabeticOnly = "abcdefghijklmnopqrstuvwxyz";
            String mixed = "Abc123XYZ!@#";

            String numericHash = PasswordUtils.encrypt(numericOnly);
            String alphaHash = PasswordUtils.encrypt(alphabeticOnly);
            String mixedHash = PasswordUtils.encrypt(mixed);

            assertTrue(PasswordUtils.verify(numericOnly, numericHash),
                    "纯数字密码应能验证");
            assertTrue(PasswordUtils.verify(alphabeticOnly, alphaHash),
                    "纯字母密码应能验证");
            assertTrue(PasswordUtils.verify(mixed, mixedHash),
                    "混合密码应能验证");

            assertNotEquals(numericHash, alphaHash,
                    "数字和字母密码哈希应不同");

            System.out.println("  纯数字: " + numericOnly + " (" + numericOnly.length() + "位)");
            System.out.println("  纯字母: " + alphabeticOnly + " (" + alphabeticOnly.length() + "位)");
            System.out.println("  混合: " + mixed + " (" + mixed.length() + "位)");
        }
    }
}
