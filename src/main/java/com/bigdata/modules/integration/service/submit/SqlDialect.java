package com.bigdata.modules.integration.service.submit;

/**
 * 生成作业配置时使用的 SQL 方言差异（目前只有标识符引用方式不同）。
 */
public enum SqlDialect {

    /** Flink SQL：标识符统一用反引号 */
    FLINK {
        @Override
        public String quote(String identifier) {
            return "`" + identifier.replace("`", "``") + "`";
        }
    };

    public abstract String quote(String identifier);

    /** SQL 字符串字面量：单引号翻倍，反斜杠转义 */
    public static String literal(String value) {
        String text = value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("'", "''");
        return "'" + text + "'";
    }
}
