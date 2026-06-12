package com.bigdata.common.sql;

import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于Apache Calcite的DDL语句解析器。
 * <p>
 * 替代JSqlParser用于检测和解析DDL语句（CREATE/ALTER/DROP/TRUNCATE TABLE），
 * 支持标准SQL和Hive扩展语法（EXTERNAL、TBLPROPERTIES、STORED AS、LOCATION等）。
 * <p>
 * 使用方式：
 * <pre>
 * DdlInfo info = CalciteDdlParser.detect(sql);
 * if (info != null) {
 *     info.getDbType();      // "CREATE" / "ALTER" / "DROP" / "TRUNCATE"
 *     info.getDatabaseName();
 *     info.getTableName();
 *     info.getHiveAttributes(); // Hive特有属性
 * }
 * </pre>
 */
public final class CalciteDdlParser {

    private static final SqlParser.Config PARSER_CONFIG = SqlParser.configBuilder()
            .setLex(Lex.MYSQL)           // MySQL兼容词法（反引号标识符、双引号字符串等）
            .build();

    private CalciteDdlParser() {}

    // ==================== 公共API ====================

    /**
     * 检测SQL是否为DDL语句并解析其结构信息。
     *
     * @param sql 原始SQL（支持Hive扩展语法）
     * @return DDL信息对象，非DDL返回null
     */
    public static DdlInfo detect(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }

        String trimmed = sql.trim();

        // 快速预检：TRUNCATE不在Calcite默认语法中，使用正则快速匹配
        DdlInfo truncateInfo = tryDetectTruncate(trimmed);
        if (truncateInfo != null) {
            truncateInfo.hiveAttributes = extractHiveAttributes(trimmed);
            return truncateInfo;
        }

        // 提取Hive属性 + 预处理SQL
        String preprocessed = preprocessForCalcite(trimmed);
        HiveTableAttributes hiveAttrs = extractHiveAttributes(trimmed);

        try {
            SqlParser parser = SqlParser.create(preprocessed, PARSER_CONFIG);
            SqlNode node = parser.parseStmt();

            DdlInfo info = buildDdlInfo(node, hiveAttrs);
            if (info != null) {
                info.fallbackMode = false;
            }
            return info;
        } catch (Exception e) {
            // Calcite解析失败时，尝试更激进的预处理（仅保留核心DDL结构）
            return tryFallbackParse(trimmed, hiveAttrs);
        }
    }

    /**
     * 从原始SQL中提取Hive表级属性（不依赖任何SQL解析器，纯正则提取）。
     */
    public static HiveTableAttributes extractHiveAttributes(String sql) {
        if (sql == null) return null;
        HiveTableAttributes attrs = new HiveTableAttributes();
        attrs.tblProperties = new LinkedHashMap<>();

        // 1. EXTERNAL 标志
        Pattern externalPattern = Pattern.compile("(?i)\\bCREATE\\s+EXTERNAL\\s+TABLE\\b");
        attrs.isExternal = externalPattern.matcher(sql).find();
        attrs.tableType = attrs.isExternal ? "EXTERNAL_TABLE" : "MANAGED_TABLE";

        // 2. TBLPROPERTIES 键值对
        Pattern tblPropsPattern = Pattern.compile("(?i)\\bTBLPROPERTIES\\s*\\(([^)]+)\\)");
        Matcher tblPropsMatcher = tblPropsPattern.matcher(sql);
        if (tblPropsMatcher.find()) {
            String propsBody = tblPropsMatcher.group(1);
            Pattern kvPattern = Pattern.compile("'([^']*)'\\s*=\\s*'([^']*)'");
            Matcher kvMatcher = kvPattern.matcher(propsBody);
            while (kvMatcher.find()) {
                String key = kvMatcher.group(1);
                String value = kvMatcher.group(2);
                attrs.tblProperties.put(key, value);
                if ("comment".equalsIgnoreCase(key)) {
                    attrs.tableComment = value;
                }
            }
        }

        // 3. STORED AS 格式
        Pattern storedAsPattern = Pattern.compile("(?i)\\bSTORED\\s+AS\\s+(\\w+)");
        Matcher storedAsMatcher = storedAsPattern.matcher(sql);
        if (storedAsMatcher.find()) {
            attrs.storageFormat = storedAsMatcher.group(1).toUpperCase();
        }

        // 4. LOCATION 路径
        Pattern locationPattern = Pattern.compile("(?i)\\bLOCATION\\s+'([^']*)'");
        Matcher locationMatcher = locationPattern.matcher(sql);
        if (locationMatcher.find()) {
            attrs.location = locationMatcher.group(1);
        }

        // 5. FIELDS TERMINATED BY 分隔符
        Pattern delimiterPattern = Pattern.compile(
                "(?i)\\bFIELDS\\s+TERMINATED\\s+BY\\s+'([^']*)'");
        Matcher delimiterMatcher = delimiterPattern.matcher(sql);
        if (delimiterMatcher.find()) {
            attrs.fieldDelimiter = unescapeDelimiter(delimiterMatcher.group(1));
        }

        return attrs;
    }

    // ==================== 内部实现 ====================

    /**
     * 预处理SQL使其能被Calcite正确解析。
     * 剥离Hive特有的子句（ROW FORMAT、SERDE、TBLPROPERTIES、STORED AS、LOCATION等），
     * 仅保留核心的 CREATE/ALTER/DROP TABLE 结构。
     */
    private static String preprocessForCalcite(String sql) {
        String result = sql;

        // 移除 IF NOT EXISTS（Calcite不支持）
        result = result.replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", "");

        // 移除 EXTERNAL 关键字（保留 CREATE TABLE）
        result = result.replaceAll("(?i)\\bEXTERNAL\\s+", "");

        // 移除 ROW FORMAT 相关子句
        result = result.replaceAll("(?i)\\bROW\\s+FORMAT\\s+DELIMITED\\b[^;]*", "");
        result = result.replaceAll("(?i)\\bROW\\s+FORMAT\\s+SERDE\\s+'[^']*'(\\s+WITH\\s+SERDEPROPERTIES\\s*\\([^)]*\\))?", "");

        // 移除 STORED AS / STORED BY
        result = result.replaceAll("(?i)\\bSTORED\\s+AS\\s+\\w+", "");
        result = result.replaceAll("(?i)\\bSTORED\\s+BY\\s+\\w+", "");

        // 移除 LOCATION
        result = result.replaceAll("(?i)\\bLOCATION\\s+'[^']*'", "");

        // 移除 TBLPROPERTIES
        result = result.replaceAll("(?i)\\bTBLPROPERTIES\\s*\\([^)]*\\)", "");

        // 移除 SERDEPROPERTIES
        result = result.replaceAll("(?i)\\bSERDEPROPERTIES\\s*\\([^)]*\\)", "");

        // 移除 SERDE '...'
        result = result.replaceAll("(?i)\\bSERDE\\s+'[^']*'", "");

        // 对于 CREATE TABLE 语句，截取到列定义右括号为止（兜底安全措施）
        if (result.trim().toUpperCase().startsWith("CREATE")) {
            result = truncateToColumnDefinition(result);
        }

        return result.trim();
    }

    /**
     * 将SQL截取到列定义的闭合括号位置，丢弃后续所有Hive子句。
     */
    private static String truncateToColumnDefinition(String sql) {
        int parenDepth = 0;
        int lastParenPos = -1;
        boolean foundOpen = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') { parenDepth++; foundOpen = true; }
            else if (c == ')') {
                parenDepth--;
                if (parenDepth == 0 && foundOpen) { lastParenPos = i; break; }
            }
        }
        if (lastParenPos > 0) {
            return sql.substring(0, lastParenPos + 1).trim();
        }
        return sql;
    }

    /**
     * 尝试用正则快速检测 TRUNCATE TABLE 语句。
     * Calcite 默认不支持 TRUNCATE 语法。
     */
    private static DdlInfo tryDetectTruncate(String sql) {
        Pattern pattern = Pattern.compile(
                "(?i)^\\s*TRUNCATE\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:`?([\\w]+)`?\\.)?`?([\\w]+)`?\\s*;?\\s*$");
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            DdlInfo info = new DdlInfo();
            info.ddlType = "TRUNCATE";
            info.databaseName = matcher.group(1);
            info.tableName = matcher.group(2);
            return info;
        }
        return null;
    }

    /**
     * 根据Calcite解析结果构建 DdlInfo 对象。
     */
    private static DdlInfo buildDdlInfo(SqlNode node, HiveTableAttributes hiveAttrs) {
        SqlKind kind = node.getKind();

        switch (kind) {
            case CREATE_TABLE:
                return handleCreateTable((SqlCreate) node, hiveAttrs);
            case DROP_TABLE:
                return handleDropTable((SqlDrop) node, hiveAttrs);
            case ALTER_TABLE:
                return handleAlterTable(node, hiveAttrs);
            default:
                return null;
        }
    }

    private static DdlInfo handleCreateTable(SqlCreate create, HiveTableAttributes hiveAttrs) {
        DdlInfo info = new DdlInfo();
        info.ddlType = "CREATE";

        // 提取表名：SqlCreate 的 operandList[0] 是表名 SqlIdentifier
        List<SqlNode> operandList = create.getOperandList();
        if (!operandList.isEmpty() && operandList.get(0) instanceof SqlIdentifier) {
            SqlIdentifier tableId = (SqlIdentifier) operandList.get(0);
            info.tableName = extractSimpleName(tableId);
            info.databaseName = extractSchemaName(tableId);
        }

        info.hiveAttributes = hiveAttrs;
        return info;
    }

    private static DdlInfo handleDropTable(SqlDrop drop, HiveTableAttributes hiveAttrs) {
        DdlInfo info = new DdlInfo();
        info.ddlType = "DROP";

        List<SqlNode> operandList = drop.getOperandList();
        for (SqlNode op : operandList) {
            if (op instanceof SqlIdentifier) {
                SqlIdentifier tableId = (SqlIdentifier) op;
                info.tableName = extractSimpleName(tableId);
                info.databaseName = extractSchemaName(tableId);
                break;
            }
        }

        info.hiveAttributes = hiveAttrs;
        return info;
    }

    private static DdlInfo handleAlterTable(SqlNode node, HiveTableAttributes hiveAttrs) {
        DdlInfo info = new DdlInfo();
        info.ddlType = "ALTER";

        // ALTER TABLE 的第一个操作数通常是表名
        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            List<SqlNode> operands = call.getOperandList();
            if (!operands.isEmpty() && operands.get(0) instanceof SqlIdentifier) {
                SqlIdentifier tableId = (SqlIdentifier) operands.get(0);
                info.tableName = extractSimpleName(tableId);
                info.databaseName = extractSchemaName(tableId);
            }
        }

        info.hiveAttributes = hiveAttrs;
        return info;
    }

    /**
     * Calcite主解析失败时的回退策略：
     * 用正则直接从原始SQL中提取DDL类型和表名。
     */
    private static DdlInfo tryFallbackParse(String rawSql, HiveTableAttributes hiveAttrs) {
        // 正则兜底：提取 CREATE/ALTER/DROP TABLE db.table
        Pattern ddlPattern = Pattern.compile(
                "(?i)^(CREATE|ALTER|DROP)\\s+(?:EXTERNAL\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`?([\\w]+)`?\\.)?`?([\\w]+)`?");
        Matcher matcher = ddlPattern.matcher(rawSql.trim());
        if (matcher.find()) {
            DdlInfo info = new DdlInfo();
            info.ddlType = matcher.group(1).toUpperCase();
            info.databaseName = matcher.group(2);
            info.tableName = matcher.group(3);
            info.hiveAttributes = hiveAttrs;
            info.fallbackMode = true;
            return info;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 SqlIdentifier 中提取简单表名（最后一部分）。
     */
    private static String extractSimpleName(SqlIdentifier id) {
        if (id.isSimple()) {
            return id.getSimple();
        }
        return id.names.get(id.names.size() - 1);
    }

    /**
     * 从 SqlIdentifier 中提取 schema/database 名称（倒数第二部分）。
     */
    private static String extractSchemaName(SqlIdentifier id) {
        if (id.names.size() >= 2) {
            return id.names.get(id.names.size() - 2);
        }
        return null;
    }

    /**
     * 反义转义分隔符（如 \\001 → \001）。
     */
    private static String unescapeDelimiter(String s) {
        if (s == null) return null;
        return s.replace("\\\\", "\\");
    }

    // ==================== 数据结构 ====================

    /**
     * DDL语句解析结果。
     */
    public static class DdlInfo {
        /** DDL类型: CREATE / ALTER / DROP / TRUNCATE */
        private String ddlType;
        /** 数据库名（可能为null表示使用默认库） */
        private String databaseName;
        /** 表名 */
        private String tableName;
        /** Hive表级属性（EXTERNAL/comment/LOCATION/STORED AS等） */
        private HiveTableAttributes hiveAttributes;
        /** 是否使用了正则回退模式（Calcite解析失败时的降级方案） */
        private boolean fallbackMode;

        public String getDbType() { return ddlType; }
        public String getDatabaseName() { return databaseName; }
        public String getTableName() { return tableName; }
        public HiveTableAttributes getHiveAttributes() { return hiveAttributes; }
        public boolean isFallbackMode() { return fallbackMode; }

        @Override
        public String toString() {
            return String.format("DdlInfo{type=%s, %s.%s, fallback=%s}",
                    ddlType, databaseName, tableName, fallbackMode);
        }
    }

    /**
     * Hive表级属性（从CREATE TABLE语句的Hive特有子句中提取）。
     */
    public static class HiveTableAttributes {
        /** 是否为外部表 */
        public boolean isExternal;
        /** 表类型: EXTERNAL_TABLE / MANAGED_TABLE */
        public String tableType;
        /** 表注释（来自 TBLPROPERTIES 'comment'） */
        public String tableComment;
        /** 存储格式（来自 STORED AS）：TEXTFILE/ORC/PARQUET等 */
        public String storageFormat;
        /** 存储路径（来自 LOCATION） */
        public String location;
        /** 字段分隔符（来自 FIELDS TERMINATED BY） */
        public String fieldDelimiter;
        /** TBLPROPERTIES 所有键值对 */
        public Map<String, String> tblProperties;
    }
}
