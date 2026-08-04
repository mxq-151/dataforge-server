package com.bigdata.common.utils;

import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 对称加解密工具，用于登录密码传输加密
 * 加密格式：密码 + 13位时间戳
 */
public class AesUtils {

    private static final long EXPIRE_MILLIS = 60_000;

    private final AES aes;

    public AesUtils(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        this.aes = new AES(Mode.ECB, Padding.PKCS5Padding, keyBytes);
    }

    /**
     * 解密并校验时间戳，返回明文密码
     * @throws IllegalArgumentException 解密失败或时间戳过期
     */
    public String decryptPassword(String cipherTextBase64) {
        byte[] cipherBytes = Base64.getDecoder().decode(cipherTextBase64);
        byte[] decrypted = aes.decrypt(cipherBytes);
        String plain = new String(decrypted, StandardCharsets.UTF_8);

        if (plain.length() <= 13) {
            throw new IllegalArgumentException("密码格式不正确");
        }
        String timestampStr = plain.substring(plain.length() - 13);
        String password = plain.substring(0, plain.length() - 13);
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("密码格式不正确");
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > EXPIRE_MILLIS) {
            throw new IllegalArgumentException("请求已过期，请重试");
        }
        return password;
    }

    /**
     * 加密（密码 + 13位时间戳）
     */
    public String encrypt(String password) {
        String plainText = password + System.currentTimeMillis();
        byte[] encrypted = aes.encrypt(plainText);
        return Base64.getEncoder().encodeToString(encrypted);
    }
}
