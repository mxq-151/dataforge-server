package com.bigdata.modules.asset.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 数据脱敏取值器 —— 把 sys_table_permission.data_masking_rule 声明的脱敏类型真正落到「值」上。
 *
 * <h3>为什么需要它</h3>
 * <p>平台既有实现只在<b>元数据</b>上标注了 {@code maskingType}（见列清单接口），
 * 但没有任何一处对<b>返回的数据值</b>做替换 —— 也就是说「配置了脱敏」与「数据被脱敏」
 * 之间断了一环。资产预览是第一个会把真实数据行送到浏览器的入口，这一环必须补上。</p>
 *
 * <h3>未知类型一律按最严处理</h3>
 * <p>脱敏类型的取值来自人工配置，出现拼写错误或未来新增类型时，
 * 「不认识就原样返回」等于把配置事故变成数据泄露。因此未知类型按 full 处理并打警告日志 ——
 * 宁可让用户看到一个全是星号的值并来问，也不要泄露一个本应脱敏的手机号。</p>
 *
 * @author dataforge
 */
public final class AssetDataMasker {

    private static final Logger log = LoggerFactory.getLogger(AssetDataMasker.class);

    /** 全遮蔽；同时作为未知类型的兜底 */
    private static final String FULL_MASK = "***";

    private AssetDataMasker() {
    }

    /**
     * 按脱敏类型处理单个值。
     *
     * @param raw         原始值；null / 空串原样返回（脱敏空值没有意义）
     * @param maskingType 脱敏类型：full / idcard / phone / email / partial
     * @return 脱敏后的值
     */
    public static String mask(String raw, String maskingType) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (maskingType == null || maskingType.isBlank()) {
            return raw;
        }
        return switch (maskingType.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> FULL_MASK;
            case "idcard" -> idCard(raw);
            case "phone" -> phone(raw);
            case "email" -> email(raw);
            case "partial" -> partial(raw);
            default -> {
                log.warn("未知脱敏类型 {}，已按 full 兜底处理（宁可过度脱敏，不可漏脱敏）", maskingType);
                yield FULL_MASK;
            }
        };
    }

    /** 身份证：保留前 6 后 4（前 6 位是地区码，业务上常需要按地区核对） */
    private static String idCard(String raw) {
        if (raw.length() <= 10) {
            return partial(raw);
        }
        return raw.substring(0, 6) + repeat('*', raw.length() - 10) + raw.substring(raw.length() - 4);
    }

    /** 手机号：保留前 3 后 4 */
    private static String phone(String raw) {
        if (raw.length() <= 7) {
            return partial(raw);
        }
        return raw.substring(0, 3) + repeat('*', raw.length() - 7) + raw.substring(raw.length() - 4);
    }

    /** 邮箱：保留首字符与域名（域名用于判断归属，用户名是敏感部分） */
    private static String email(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0) {
            return partial(raw);
        }
        return raw.charAt(0) + "***" + raw.substring(at);
    }

    /** 通用部分遮蔽：保留首尾字符，中间定长星号（不泄露长度） */
    private static String partial(String raw) {
        if (raw.length() == 1) {
            return FULL_MASK;
        }
        if (raw.length() == 2) {
            return raw.charAt(0) + "*";
        }
        return raw.charAt(0) + "***" + raw.charAt(raw.length() - 1);
    }

    private static String repeat(char ch, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
