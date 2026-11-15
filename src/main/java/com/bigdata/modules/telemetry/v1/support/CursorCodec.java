package com.bigdata.modules.telemetry.v1.support;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 游标编解码与条件指纹。
 *
 * <p>游标里带 filter/timeRange 指纹：调用方改了过滤条件却继续用旧游标时返回 CURSOR_MISMATCH，
 * 而不是悄悄给出另一套结果。</p>
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(Object value, ObjectMapper mapper) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("游标编码失败", e);
        }
    }

    public static <T> T decode(String cursor, Class<T> type, ObjectMapper mapper) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.CURSOR_EXPIRED,
                    "游标不可解析或已过期，请从第一页重新查询");
        }
    }

    /** 条件指纹：调用方不应对指纹做任何假设，只用于判断条件是否变化。 */
    public static String hash(ObjectMapper mapper, Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            if (canonical.length() > 0) {
                canonical.append('|');
            }
            try {
                canonical.append(part == null ? "null" : mapper.writeValueAsString(part));
            } catch (Exception e) {
                canonical.append(part == null ? "null" : part.toString());
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
