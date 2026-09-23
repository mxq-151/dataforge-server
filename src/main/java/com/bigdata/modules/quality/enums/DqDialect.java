package com.bigdata.modules.quality.enums;

import com.bigdata.modules.devops.enums.DatasourceType;

import java.util.Arrays;

/**
 * 校验 SQL 的方言族 —— DqSqlBuilder 按它决定标识符引用符与类型转换写法。
 *
 * <p>刻意不直接用 {@link DatasourceType}：引擎有十几种，但真正影响校验 SQL 写法的
 * 只有三类（引用符 + 字符串转换函数 + LIMIT 支持）。把差异收敛到这一处，
 * 新增引擎时只需在 {@link #of} 里补一行映射。</p>
 */
public enum DqDialect {

    /** 反引号标识符；字符串转换 CAST(x AS CHAR)；支持 LIMIT */
    MYSQL("`", "CHAR", true),

    /** 双引号标识符；字符串转换 CAST(x AS VARCHAR)；支持 LIMIT */
    POSTGRESQL("\"", "VARCHAR", true),

    /** 反引号标识符；字符串转换 CAST(x AS STRING)；支持 LIMIT */
    HIVE("`", "STRING", true);

    private final String quote;
    private final String charType;
    private final boolean limitSupported;

    DqDialect(String quote, String charType, boolean limitSupported) {
        this.quote = quote;
        this.charType = charType;
        this.limitSupported = limitSupported;
    }

    /** 标识符引用符（前后各一个） */
    public String getQuote() {
        return quote;
    }

    /** 转字符串时使用的类型名 */
    public String getCharType() {
        return charType;
    }

    public boolean isLimitSupported() {
        return limitSupported;
    }

    /**
     * 把标识符（表名 / 库名 / 字段名）包装成该方言的引用标识符。
     *
     * <p>刻意一律加引用符：库表元数据来自 information_schema / pg_catalog，
     * 拿到的是物理名的<b>真实大小写</b>，加引用符才能原样命中；
     * 同时避免表名恰好是保留字（如 order / group）时校验 SQL 语法失败。</p>
     */
    public String quoteIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        // 引用符内部的同类引用符按 SQL 标准双写转义（"a""b" / `a``b`）
        return quote + raw.replace(quote, quote + quote) + quote;
    }

    /**
     * 把字符串值渲染为 SQL 字面量（含定界引号）。
     *
     * <p>PostgreSQL 默认 standard_conforming_strings=on，反斜杠是普通字符，
     * 只能靠双写单引号转义；MySQL / Hive 默认把反斜杠当转义符，
     * 需要同时转义反斜杠与单引号。两套写法混用会导致值域字典被注入或语法报错，
     * 因此收敛到这里。</p>
     */
    public String literal(String raw) {
        if (raw == null) {
            return "NULL";
        }
        String escaped = this == POSTGRESQL
                ? raw.replace("'", "''")
                : raw.replace("\\", "\\\\").replace("'", "\\'");
        return "'" + escaped + "'";
    }

    /**
     * 按数据源引擎解析方言族。
     *
     * <p>未识别的引擎一律落到 {@link #MYSQL}（校验对象以关系库为主，
     * 且三种方言里 MySQL 系的语法最宽松）；调用方若对精度有要求，
     * 应在配置规则时显式约束数据源类型。</p>
     */
    public static DqDialect of(DatasourceType type) {
        if (type == null) {
            return MYSQL;
        }
        return switch (type) {
            case POSTGRESQL -> POSTGRESQL;
            case HIVE, KYUUBI, SPARK -> HIVE;
            default -> MYSQL;
        };
    }

    /** 供测试断言方言覆盖度 */
    public static DqDialect ofCode(String code) {
        if (code == null) {
            return MYSQL;
        }
        DatasourceType t = Arrays.stream(DatasourceType.values())
                .filter(v -> v.getCode().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
        return of(t);
    }
}
