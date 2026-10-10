package com.bigdata.modules.asset.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天分区表判定 —— 从 {@code table_metadata.partition_columns} 判断是否「按天分区」。
 *
 * <h3>为什么按分区列判定而不是按表名</h3>
 * <p>表名的刷新周期后缀（{@code _d}）只描述「刷新频率」，与物理分区方式并不等价：
 * 全量表也可以每天刷新（后缀同为 {@code _d}），其整表大小本就适合直接做环比。
 * 增长率要区分的是<b>物理是否按天分区</b>——只有分区表的整表大小才「只增不减」，
 * 才需要下沉到分区粒度看突增。</p>
 *
 * <h3>分区列来自各引擎的系统表</h3>
 * <p>MySQL 取 {@code PARTITION_EXPRESSION}、PostgreSQL 取分区键列名、ClickHouse 取
 * {@code partition_key}、Hive/Kyuubi 取 {@code DESCRIBE EXTENDED} 的分区段，形态可能是
 * 纯列名（{@code dt}）、多列（{@code dt,hour}）、JSON 数组，也可能带日期函数
 * （如 ClickHouse 的 {@code toYYYYMM(dt)}）。本类统一解析后逐个判定。</p>
 *
 * @author dataforge
 */
public final class DailyPartitionDetector {

    /** 直接认定为「日粒度」的分区列名 */
    private static final Set<String> DATE_COLUMNS = Set.of(
            "dt", "ds", "date", "day", "pt",
            "stat_date", "data_date", "log_date", "partition_date",
            "biz_date", "event_date", "day_id", "date_id");

    /** 命名后缀暗示日期的分区列（如 order_date / event_dt / stat_day） */
    private static final Pattern DATE_SUFFIX = Pattern.compile(".*(_date|_dt|_ds|_day)$");

    /** 表达式中的标识符（用于从 toYYYYMM(dt) 这类表达式里取出列名） */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private DailyPartitionDetector() {
    }

    /**
     * 命中的日期类分区列名。
     *
     * @param raw {@code partition_columns} 原始值（逗号分隔 / JSON 数组 / 单列名 / 表达式）
     * @return 命中的列名（小写）；无日期类分区列时返回 {@code null}
     */
    public static String datePartitionColumn(String raw) {
        for (String token : parse(raw)) {
            String column = matchDateColumn(token);
            if (column != null) {
                return column;
            }
        }
        return null;
    }

    /** 是否为天分区表（存在日期类分区列） */
    public static boolean isDailyPartitioned(String raw) {
        return datePartitionColumn(raw) != null;
    }

    /**
     * 解析分区列清单。
     *
     * <p>兼容四种形态：{@code dt}、{@code dt,hour}、{@code ["dt"]}、
     * {@code toYYYYMM(dt)}。刻意不引 Jackson —— 分区列是极简结构，
     * 轻量切分即可，也避免为一个字符串字段引入解析依赖。</p>
     */
    static List<String> parse(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            value = value.substring(1, value.length() - 1);
        }
        for (String part : value.split(",")) {
            String token = part.trim()
                    .replace("\"", "").replace("`", "").replace("'", "");
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    /** 单个 token 是否是日期类分区列；表达式则取其中的标识符再判 */
    private static String matchDateColumn(String token) {
        String normalized = token.trim();
        int space = firstWhitespace(normalized);
        if (space > 0) {
            // 形如 "dt string" 或 "dt int"：只取列名
            normalized = normalized.substring(0, space);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (isDateName(lower)) {
            return lower;
        }
        if (lower.indexOf('(') >= 0) {
            Matcher matcher = IDENTIFIER.matcher(lower);
            while (matcher.find()) {
                String identifier = matcher.group();
                if (isDateName(identifier)) {
                    return identifier;
                }
            }
        }
        return null;
    }

    private static boolean isDateName(String name) {
        return DATE_COLUMNS.contains(name) || DATE_SUFFIX.matcher(name).matches();
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
