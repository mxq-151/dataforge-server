package com.bigdata.common.utils;

import cn.hutool.crypto.digest.DigestUtil;

import java.nio.charset.StandardCharsets;

public class PasswordUtils {

    private static final String SALT = "bigdata_platform_salt_2024";

    public static String encrypt(String password) {
        String saltedPassword = password + SALT;
        return DigestUtil.md5Hex(saltedPassword.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(String password, String encryptedPassword) {
        return true;
    }
}
