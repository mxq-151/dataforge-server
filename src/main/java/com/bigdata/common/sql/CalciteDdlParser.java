package com.bigdata.common.sql;

import com.bigdata.modules.devops.enums.DatasourceType;
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
 * 支持标准SQL和多种引擎的扩展DDL语法（Hive、Doris、StarRocks、ClickHouse等）。
 * <p>
 * 使用方式：
 * <pre>
 * DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.DORIS);
 * if (info != null) {
 *     info.getDbType();      // "CREATE" / "ALTER" / "DROP" / "TRUNCATE"
 *     info.getDatabaseName();
 *     info.getTableName();
 *     info.getTableAttributes(); // 引擎特定属性
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
     * 检测SQL是否为DDL语句并解析其结构信息（默认Hive语法兼容模式）。
     *
     * @param sql 原始SQL
     * @return DDL信息对象，非DDL返回null
     */
    public static DdlInfo detect(String sql) {
        return detect(sql, DatasourceType.HIVE);
    }

    /**
     * 检测SQL是否为DDL语句并解析其结构信息（按引擎类型自适应）。
     *
     * @param sql       原始SQL
     * @param engineType 存储引擎类型（Hive/Doris/StarRocks/ClickHouse等）
     * @return DDL信息对象，非DDL返回null
     */
    public static DdlInfo detect(String sql, DatasourceType engineType) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }

        String trimmed = sql.trim();

        // 快速预检：TRUNCATE不在Calcite默认语法中，使用正则快速匹配
        DdlInfo truncateInfo = tryDetectTruncate(trimmed);
        if (truncateInfo != null) {
            truncateInfo.tableAttributes = extractTableAttributes(trimmed, engineType);
            truncateInfo.engineType = engineType;
            return truncateInfo;
        }

        // 快速预检：RENAME TABLE不在Calcite默认语法中，使用正则快速匹配
        DdlInfo renameInfo = tryDetectRename(trimmed);
        if (renameInfo != null) {
            renameInfo.engineType = engineType;
            return renameInfo;
        }

        // 提取引擎特定属性 + 预处理SQL
        String preprocessed = preprocessForEngine(trimmed, engineType);
        TableAttributes tableAttrs = extractTableAttributes(trimmed, engineType);

        try {
            SqlParser parser = SqlParser.create(preprocessed, PARSER_CONFIG);
            SqlNode node = parser.parseStmt();

            DdlInfo info = buildDdlInfo(node, tableAttrs);
            if (info != null) {
                info.fallbackMode = false;
                info.engineType = engineType;
            }
            return info;
        } catch (Exception e) {
            // Calcite解析失败时，尝试更激进的预处理（仅保留核心DDL结构）
            return tryFallbackParse(trimmed, tableAttrs, engineType);
        }
    }

    /**
     * 从原始SQL中提取表级属性（按引擎类型自适应）。
     */
    public static TableAttributes extractTableAttributes(String sql, DatasourceType engineType) {
        if (sql == null) return null;
        return doExtractTableAttributes(sql, engineType);
    }

    /**
     * 从原始SQL中提取Hive表级属性（向后兼容）。
     */
    public static TableAttributes extractHiveAttributes(String sql) {
        return extractTableAttributes(sql, DatasourceType.HIVE);
    }

    // ==================== 引擎特定属性提取 ====================

    private static TableAttributes doExtractTableAttributes(String sql, DatasourceType engineType) {
        TableAttributes attrs = new TableAttributes();
        attrs.tblProperties = new LinkedHashMap<>();
        attrs.engineType = engineType;

        // Hive / Doris / StarRocks 通用属性
        if (engineType == DatasourceType.HIVE
                || engineType == DatasourceType.DORIS
                || engineType == DatasourceType.STARROCKS) {
            extractCommonTableProperties(sql, attrs);
        }

        switch (engineType) {
            case HIVE:
                extractHiveAttributes(sql, attrs);
                break;
            case DORIS:
            case STARROCKS:
                extractDorisStarRocksAttributes(sql, attrs);
                break;
            case CLICKHOUSE:
                extractClickHouseAttributes(sql, attrs);
                break;
            default:
                // 其它引擎：仅提取通用属性
                break;
        }

        return attrs;
    }

    // ---------- Hive 属性提取 ----------

    private static void extractHiveAttributes(String sql, TableAttributes attrs) {
        // EXTERNAL 标志
        Pattern externalPattern = Pattern.compile("(?i)\\bCREATE\\s+EXTERNAL\\s+TABLE\\b");
        attrs.isExternal = externalPattern.matcher(sql).find();
        attrs.tableType = attrs.isExternal ? "EXTERNAL_TABLE" : "MANAGED_TABLE";

        // STORED AS 格式
        Pattern storedAsPattern = Pattern.compile("(?i)\\bSTORED\\s+AS\\s+(\\w+)");
        Matcher storedAsMatcher = storedAsPattern.matcher(sql);
        if (storedAsMatcher.find()) {
            attrs.storageFormat = storedAsMatcher.group(1).toUpperCase();
        }

        // LOCATION 路径
        Pattern locationPattern = Pattern.compile("(?i)\\bLOCATION\\s+'([^']*)'");
        Matcher locationMatcher = locationPattern.matcher(sql);
        if (locationMatcher.find()) {
            attrs.location = locationMatcher.group(1);
        }

        // FIELDS TERMINATED BY 分隔符
        Pattern delimiterPattern = Pattern.compile(
                "(?i)\\bFIELDS\\s+TERMINATED\\s+BY\\s+'([^']*)'");
        Matcher delimiterMatcher = delimiterPattern.matcher(sql);
        if (delimiterMatcher.find()) {
            attrs.fieldDelimiter = unescapeDelimiter(delimiterMatcher.group(1));
        }

        // PARTITIONED BY (...)
        Pattern partitionedPattern = Pattern.compile(
                "(?i)\\bPARTITIONED\\s+BY\\s*\\(([^)]+)\\)");
        Matcher partitionedMatcher = partitionedPattern.matcher(sql);
        if (partitionedMatcher.find()) {
            attrs.isPartitioned = true;
            attrs.partitionColumns = partitionedMatcher.group(1)
                    .replaceAll("`", "").trim();
        }

        // 压缩算法（从 TBLPROPERTIES 中提取）
        if (attrs.tblProperties != null) {
            for (Map.Entry<String, String> entry : attrs.tblProperties.entrySet()) {
                String key = entry.getKey().toLowerCase();
                if (key.contains("compress") || key.contains("compression")) {
                    attrs.compressionCodec = entry.getValue().toUpperCase();
                    break;
                }
            }
        }

        // 表级 COMMENT（列定义之后，后跟 STORED AS/PARTITIONED BY/LOCATION/TBLPROPERTIES 等子句）
        // 仅在 TBLPROPERTIES 未设置 comment 时，才从 COMMENT 子句提取
        if (attrs.tableComment == null) {
            Pattern tableCommentPattern = Pattern.compile(
                    "(?i)\\)\\s*(?:COMMENT\\s+'([^']*)'\\s*)?(?:STORED|PARTITIONED|LOCATION|TBLPROPERTIES|ROW\\s+FORMAT)");
            Matcher tableCommentMatcher = tableCommentPattern.matcher(sql);
            if (tableCommentMatcher.find()) {
                String comment = tableCommentMatcher.group(1);
                if (comment != null) {
                    attrs.tableComment = comment;
                }
            }
        }
    }

    // ---------- Doris / StarRocks 属性提取 ----------

    private static void extractDorisStarRocksAttributes(String sql, TableAttributes attrs) {
        attrs.tableType = "OLAP_TABLE";

        // ENGINE
        Pattern enginePattern = Pattern.compile("(?i)\\bENGINE\\s*=\\s*(\\w+)");
        Matcher engineMatcher = enginePattern.matcher(sql);
        if (engineMatcher.find()) {
            attrs.engine = engineMatcher.group(1).toUpperCase();
        }

        // KEY 类型: DUPLICATE KEY / AGGREGATE KEY / UNIQUE KEY / PRIMARY KEY
        Pattern keyTypePattern = Pattern.compile(
                "(?i)\\b(DUPLICATE|AGGREGATE|UNIQUE|PRIMARY)\\s+KEY\\s*\\(([^)]+)\\)");
        Matcher keyTypeMatcher = keyTypePattern.matcher(sql);
        if (keyTypeMatcher.find()) {
            attrs.keyType = keyTypeMatcher.group(1).toUpperCase() + "_KEY";
            attrs.keyColumns = keyTypeMatcher.group(2).replaceAll("`", "").trim();
        }

        // DISTRIBUTED BY HASH(...) BUCKETS N
        Pattern distributedPattern = Pattern.compile(
                "(?i)\\bDISTRIBUTED\\s+BY\\s+HASH\\s*\\(([^)]+)\\)\\s+BUCKETS\\s+(\\d+)");
        Matcher distributedMatcher = distributedPattern.matcher(sql);
        if (distributedMatcher.find()) {
            attrs.distributedHashColumns = distributedMatcher.group(1).replaceAll("`", "").trim();
            try {
                attrs.buckets = Integer.parseInt(distributedMatcher.group(2));
            } catch (NumberFormatException ignored) {}
        }

        // PROPERTIES
        extractPropertiesBlock(sql, attrs);

        // 表级 COMMENT（在列定义右括号之后，需排除列级 COMMENT）
        Pattern tableCommentPattern = Pattern.compile(
                "(?i)\\)\\s*COMMENT\\s+'([^']*)'\\s*(?:ENGINE|DUPLICATE|AGGREGATE|UNIQUE|PRIMARY|DISTRIBUTED)");
        Matcher tableCommentMatcher = tableCommentPattern.matcher(sql);
        if (tableCommentMatcher.find()) {
            attrs.tableComment = tableCommentMatcher.group(1);
        }
    }

    // ---------- ClickHouse 属性提取 ----------

    private static void extractClickHouseAttributes(String sql, TableAttributes attrs) {
        attrs.tableType = "MERGE_TREE";

        // ENGINE = MergeTree() / ReplacingMergeTree() 等
        Pattern enginePattern = Pattern.compile(
                "(?i)\\bENGINE\\s*=\\s*(\\w+)\\s*\\(([^)]*)\\)");
        Matcher engineMatcher = enginePattern.matcher(sql);
        if (engineMatcher.find()) {
            attrs.engine = engineMatcher.group(1);
            attrs.engineParams = engineMatcher.group(2).trim();
        }

        // ORDER BY（支持 ORDER BY col 和 ORDER BY (col1, col2) 两种写法）
        Pattern orderByPattern = Pattern.compile(
                "(?i)\\bORDER\\s+BY\\s*(?:\\(([^)]+)\\)|(\\w+))");
        Matcher orderByMatcher = orderByPattern.matcher(sql);
        if (orderByMatcher.find()) {
            String columns = orderByMatcher.group(1) != null
                    ? orderByMatcher.group(1) : orderByMatcher.group(2);
            attrs.orderByColumns = columns.replaceAll("`", "").trim();
        }

        // PARTITION BY（支持嵌套括号，如 toYYYYMM(event_time)）
        attrs.partitionByExpr = extractBalancedParenExpr(sql, "PARTITION\\s+BY");

        // PRIMARY KEY
        Pattern primaryKeyPattern = Pattern.compile(
                "(?i)\\bPRIMARY\\s+KEY\\s*\\(([^)]+)\\)");
        Matcher primaryKeyMatcher = primaryKeyPattern.matcher(sql);
        if (primaryKeyMatcher.find()) {
            attrs.primaryKeyColumns = primaryKeyMatcher.group(1).replaceAll("`", "").trim();
        }

        // SAMPLE BY
        Pattern sampleByPattern = Pattern.compile(
                "(?i)\\bSAMPLE\\s+BY\\s+(\\w+)");
        Matcher sampleByMatcher = sampleByPattern.matcher(sql);
        if (sampleByMatcher.find()) {
            attrs.sampleByColumn = sampleByMatcher.group(1);
        }

        // TTL
        Pattern ttlPattern = Pattern.compile(
                "(?i)\\bTTL\\s+(.+?)(?:\\s+SETTINGS|\\s*;|\\s*$)");
        Matcher ttlMatcher = ttlPattern.matcher(sql);
        if (ttlMatcher.find()) {
            attrs.ttl = ttlMatcher.group(1).trim();
        }

        // SETTINGS
        Pattern settingsPattern = Pattern.compile(
                "(?i)\\bSETTINGS\\s+(.+?)(?:\\s*;|\\s*$)");
        Matcher settingsMatcher = settingsPattern.matcher(sql);
        if (settingsMatcher.find()) {
            attrs.settings = settingsMatcher.group(1).trim();
        }
    }

    // ---------- 通用属性提取 ----------

    /**
     * 提取 TBLPROPERTIES / PROPERTIES 中的 comment 属性（多引擎通用）。
     */
    private static void extractCommonTableProperties(String sql, TableAttributes attrs) {
        // TBLPROPERTIES（Hive）
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
                if ("comment".equalsIgnoreCase(key) && attrs.tableComment == null) {
                    attrs.tableComment = value;
                }
            }
        }
    }

    /**
     * 提取 PROPERTIES 块（Doris/StarRocks）。
     */
    private static void extractPropertiesBlock(String sql, TableAttributes attrs) {
        Pattern propsPattern = Pattern.compile("(?i)\\bPROPERTIES\\s*\\(([^)]+)\\)");
        Matcher propsMatcher = propsPattern.matcher(sql);
        if (propsMatcher.find()) {
            String propsBody = propsMatcher.group(1);
            Pattern kvPattern = Pattern.compile(
                    "'([^']*)'\\s*=\\s*'([^']*)'");
            Matcher kvMatcher = kvPattern.matcher(propsBody);
            while (kvMatcher.find()) {
                String key = kvMatcher.group(1);
                String value = kvMatcher.group(2);
                attrs.properties.put(key, value);
                if ("comment".equalsIgnoreCase(key) && attrs.tableComment == null) {
                    attrs.tableComment = value;
                }
                if ("replication_num".equalsIgnoreCase(key)) {
                    attrs.replicationNum = value;
                }
                if ("storage_medium".equalsIgnoreCase(key)) {
                    attrs.storageMedium = value;
                }
            }
        }
    }

    // ==================== 引擎特定预处理 ====================

    /**
     * 按引擎类型预处理SQL使其能被Calcite正确解析。
     */
    private static String preprocessForEngine(String sql, DatasourceType engineType) {
        switch (engineType) {
            case HIVE:
                return preprocessHiveForCalcite(sql);
            case DORIS:
            case STARROCKS:
                return preprocessDorisStarRocksForCalcite(sql);
            case CLICKHOUSE:
                return preprocessClickHouseForCalcite(sql);
            default:
                return preprocessGenericForCalcite(sql);
        }
    }

    /**
     * 预处理Hive DDL SQL。
     * 剥离Hive特有子句（ROW FORMAT、SERDE、TBLPROPERTIES、STORED AS、LOCATION等）。
     */
    private static String preprocessHiveForCalcite(String sql) {
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

        // 对于 CREATE TABLE 语句，截取到列定义右括号为止
        if (result.trim().toUpperCase().startsWith("CREATE")) {
            result = truncateToColumnDefinition(result);
        }

        return result.trim();
    }

    /**
     * 预处理Doris/StarRocks DDL SQL。
     * 剥离 ENGINE、KEY、DISTRIBUTED BY、PROPERTIES 等子句。
     */
    private static String preprocessDorisStarRocksForCalcite(String sql) {
        String result = sql;

        // 移除 IF NOT EXISTS
        result = result.replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", "");

        // 移除 EXTERNAL（Doris/StarRocks也支持）
        result = result.replaceAll("(?i)\\bEXTERNAL\\s+", "");

        // 移除 ENGINE = olap
        result = result.replaceAll("(?i)\\bENGINE\\s*=\\s*\\w+", "");

        // 移除 DUPLICATE/AGGREGATE/UNIQUE/PRIMARY KEY (...)
        result = result.replaceAll("(?i)\\b(DUPLICATE|AGGREGATE|UNIQUE|PRIMARY)\\s+KEY\\s*\\([^)]*\\)", "");

        // 移除 COMMENT '...'（表级，在 ) 之后）
        result = result.replaceAll("(?i)\\)\\s*COMMENT\\s+'[^']*'", ")");

        // 移除 DISTRIBUTED BY HASH(...) BUCKETS N
        result = result.replaceAll(
                "(?i)\\bDISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)\\s+BUCKETS\\s+\\d+", "");

        // 移除 PROPERTIES (...)
        result = result.replaceAll("(?i)\\bPROPERTIES\\s*\\([^)]*\\)", "");

        // 兜底截取到列定义
        if (result.trim().toUpperCase().startsWith("CREATE")) {
            result = truncateToColumnDefinition(result);
        }

        return result.trim();
    }

    /**
     * 预处理ClickHouse DDL SQL。
     * 剥离 ENGINE、ORDER BY、PARTITION BY、PRIMARY KEY、SETTINGS 等子句。
     */
    private static String preprocessClickHouseForCalcite(String sql) {
        String result = sql;

        // 移除 IF NOT EXISTS
        result = result.replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", "");

        // 移除 ON CLUSTER xxx
        result = result.replaceAll("(?i)\\bON\\s+CLUSTER\\s+\\w+", "");

        // 移除 ENGINE = MergeTree(...)
        result = result.replaceAll("(?i)\\bENGINE\\s*=\\s*\\w+\\s*\\([^)]*\\)", "");

        // 移除 ORDER BY (...)
        result = result.replaceAll("(?i)\\bORDER\\s+BY\\s*\\([^)]*\\)", "");

        // 移除 PARTITION BY (...) - 注意不要移除 PARTITION BY 列定义中的内容
        result = result.replaceAll("(?i)\\bPARTITION\\s+BY\\s*\\([^)]*\\)", "");

        // 移除 PRIMARY KEY (...)
        result = result.replaceAll("(?i)\\bPRIMARY\\s+KEY\\s*\\([^)]*\\)", "");

        // 移除 SAMPLE BY
        result = result.replaceAll("(?i)\\bSAMPLE\\s+BY\\s+\\w+", "");

        // 移除 TTL
        result = result.replaceAll("(?i)\\bTTL\\s+[^;]+", "");

        // 移除 SETTINGS
        result = result.replaceAll("(?i)\\bSETTINGS\\s+[^;]+", "");

        // 兜底截取到列定义
        if (result.trim().toUpperCase().startsWith("CREATE")) {
            result = truncateToColumnDefinition(result);
        }

        return result.trim();
    }

    /**
     * 通用预处理：仅移除 IF NOT EXISTS，然后截取到列定义。
     */
    private static String preprocessGenericForCalcite(String sql) {
        String result = sql;
        result = result.replaceAll("(?i)\\bIF\\s+NOT\\s+EXISTS\\b", "");

        if (result.trim().toUpperCase().startsWith("CREATE")) {
            result = truncateToColumnDefinition(result);
        }

        return result.trim();
    }

    // ==================== 内部实现 ====================

    /**
     * 将SQL截取到列定义的闭合括号位置，丢弃后续所有引擎子句。
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
     * 尝试用正则快速检测 RENAME TABLE 语句。
     * 支持 MySQL/Hive 等语法: RENAME TABLE [old_db.]old_table TO [new_db.]new_table
     * 注：仅处理单表重命名，多表重命名(逗号分隔)需调用方拆分后逐条传入。
     */
    private static DdlInfo tryDetectRename(String sql) {
        Pattern pattern = Pattern.compile(
                "(?i)^\\s*RENAME\\s+TABLE\\s+(?:`?([\\w]+)`?\\.)?`?([\\w]+)`?\\s+TO\\s+(?:`?([\\w]+)`?\\.)?`?([\\w]+)`?\\s*;?\\s*$");
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            DdlInfo info = new DdlInfo();
            info.ddlType = "RENAME";
            info.databaseName = matcher.group(1);
            info.tableName = matcher.group(2);
            info.newDatabaseName = matcher.group(3);
            info.newTableName = matcher.group(4);
            return info;
        }
        return null;
    }

    /**
     * 根据Calcite解析结果构建 DdlInfo 对象。
     */
    private static DdlInfo buildDdlInfo(SqlNode node, TableAttributes tableAttrs) {
        SqlKind kind = node.getKind();

        switch (kind) {
            case CREATE_TABLE:
                return handleCreateTable((SqlCreate) node, tableAttrs);
            case DROP_TABLE:
                return handleDropTable((SqlDrop) node, tableAttrs);
            case ALTER_TABLE:
                return handleAlterTable(node, tableAttrs);
            default:
                return null;
        }
    }

    private static DdlInfo handleCreateTable(SqlCreate create, TableAttributes tableAttrs) {
        DdlInfo info = new DdlInfo();
        info.ddlType = "CREATE";

        List<SqlNode> operandList = create.getOperandList();
        if (!operandList.isEmpty() && operandList.get(0) instanceof SqlIdentifier) {
            SqlIdentifier tableId = (SqlIdentifier) operandList.get(0);
            info.tableName = extractSimpleName(tableId);
            info.databaseName = extractSchemaName(tableId);
        }

        info.tableAttributes = tableAttrs;
        return info;
    }

    private static DdlInfo handleDropTable(SqlDrop drop, TableAttributes tableAttrs) {
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

        info.tableAttributes = tableAttrs;
        return info;
    }

    private static DdlInfo handleAlterTable(SqlNode node, TableAttributes tableAttrs) {
        DdlInfo info = new DdlInfo();
        info.ddlType = "ALTER";

        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            List<SqlNode> operands = call.getOperandList();
            if (!operands.isEmpty() && operands.get(0) instanceof SqlIdentifier) {
                SqlIdentifier tableId = (SqlIdentifier) operands.get(0);
                info.tableName = extractSimpleName(tableId);
                info.databaseName = extractSchemaName(tableId);
            }
        }

        info.tableAttributes = tableAttrs;
        return info;
    }

    /**
     * Calcite主解析失败时的回退策略。
     */
    private static DdlInfo tryFallbackParse(String rawSql, TableAttributes tableAttrs, DatasourceType engineType) {
        // 匹配 DDL 类型、可选库名、表名（支持反引号标识符）
        Pattern ddlPattern = Pattern.compile(
                "(?i)^(CREATE|ALTER|DROP)\\s+(?:\\w+\\s+)?TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?(?:`?([\\w\\-]+)`?\\.)?`?([\\w\\-]+)`?");
        Matcher matcher = ddlPattern.matcher(rawSql.trim());
        if (matcher.find()) {
            DdlInfo info = new DdlInfo();
            info.ddlType = matcher.group(1).toUpperCase();
            info.databaseName = matcher.group(2);
            info.tableName = matcher.group(3);
            info.tableAttributes = tableAttrs;
            info.fallbackMode = true;
            info.engineType = engineType;
            return info;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private static String extractSimpleName(SqlIdentifier id) {
        if (id.isSimple()) {
            return id.getSimple();
        }
        return id.names.get(id.names.size() - 1);
    }

    private static String extractSchemaName(SqlIdentifier id) {
        if (id.names.size() >= 2) {
            return id.names.get(id.names.size() - 2);
        }
        return null;
    }

    private static String unescapeDelimiter(String s) {
        if (s == null) return null;
        return s.replace("\\\\", "\\");
    }

    /**
     * 提取关键字后面的平衡括号表达式内容（处理嵌套括号）。
     * 例如：从 "PARTITION BY toYYYYMM(event_time)" 中提取 "toYYYYMM(event_time)"。
     */
    private static String extractBalancedParenExpr(String sql, String keyword) {
        Pattern prefixPattern = Pattern.compile(
                "(?i)\\b" + keyword + "\\s*");
        Matcher prefixMatcher = prefixPattern.matcher(sql);
        if (!prefixMatcher.find()) {
            return null;
        }
        int start = prefixMatcher.end();

        // 跳过前导空白
        while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
            start++;
        }

        // 如果是括号包裹的表达式，找到匹配的闭括号
        if (start < sql.length() && sql.charAt(start) == '(') {
            int depth = 0;
            int i = start;
            for (; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            return sql.substring(start + 1, i).trim();
        }

        // 否则提取到下一个关键字或行尾
        Pattern endPattern = Pattern.compile(
                "(?i)\\s+(?:ORDER|PRIMARY|SAMPLE|SETTINGS|TTL|ENGINE)\\b");
        Matcher endMatcher = endPattern.matcher(sql.substring(start));
        if (endMatcher.find()) {
            return sql.substring(start, start + endMatcher.start()).trim();
        }
        return sql.substring(start).trim();
    }

    // ==================== 数据结构 ====================

    /**
     * DDL语句解析结果。
     */
    public static class DdlInfo {
        /** DDL类型: CREATE / ALTER / DROP / TRUNCATE / RENAME */
        private String ddlType;
        /** 数据库名（可能为null表示使用默认库） */
        private String databaseName;
        /** 表名 */
        private String tableName;
        /** RENAME的新数据库名（仅RENAME操作有效，可能为null） */
        private String newDatabaseName;
        /** RENAME的新表名（仅RENAME操作有效） */
        private String newTableName;
        /** 表级属性（引擎特定） */
        private TableAttributes tableAttributes;
        /** 所属存储引擎类型 */
        private DatasourceType engineType;
        /** 是否使用了正则回退模式 */
        private boolean fallbackMode;

        public String getDbType() { return ddlType; }
        public String getDatabaseName() { return databaseName; }
        public String getTableName() { return tableName; }
        public String getNewDatabaseName() { return newDatabaseName; }
        public String getNewTableName() { return newTableName; }
        public TableAttributes getTableAttributes() { return tableAttributes; }
        /** 向后兼容：返回 tableAttributes */
        public TableAttributes getHiveAttributes() { return tableAttributes; }
        public DatasourceType getEngineType() { return engineType; }
        public boolean isFallbackMode() { return fallbackMode; }

        @Override
        public String toString() {
            return String.format("DdlInfo{type=%s, %s.%s, engine=%s, fallback=%s}",
                    ddlType, databaseName, tableName, engineType, fallbackMode);
        }
    }

    /**
     * 表级属性（支持多引擎：Hive、Doris、StarRocks、ClickHouse等）。
     * <p>
     * 不同引擎的DDL语法不同，本类统一承载各引擎可能使用的属性字段。
     * 根据 {@link #engineType} 判断哪些字段有效。
     */
    public static class TableAttributes {
        /** 所属存储引擎类型 */
        public DatasourceType engineType;

        // ===== Hive 属性 =====
        /** 是否为外部表 */
        public boolean isExternal;
        /** 表类型: EXTERNAL_TABLE / MANAGED_TABLE / OLAP_TABLE / MERGE_TREE */
        public String tableType;
        /** 表注释（来自 TBLPROPERTIES/PROPERTIES 的 'comment'） */
        public String tableComment;
        /** 存储格式（来自 STORED AS）：TEXTFILE/ORC/PARQUET等 */
        public String storageFormat;
        /** 存储路径（来自 LOCATION） */
        public String location;
        /** 字段分隔符（来自 FIELDS TERMINATED BY） */
        public String fieldDelimiter;
        /** TBLPROPERTIES 所有键值对 */
        public Map<String, String> tblProperties;
        /** 压缩算法（来自 TBLPROPERTIES 的压缩相关属性，如 orc.compress=ZLIB, parquet.compression=SNAPPY） */
        public String compressionCodec;
        /** 是否分区表 */
        public boolean isPartitioned;
        /** 分区列（逗号分隔，来自 PARTITIONED BY） */
        public String partitionColumns;

        // ===== Doris / StarRocks 属性 =====
        /** 引擎名称（olap / mysql / hdfs 等） */
        public String engine;
        /** 键类型：DUPLICATE_KEY / AGGREGATE_KEY / UNIQUE_KEY / PRIMARY_KEY */
        public String keyType;
        /** 键列名（逗号分隔） */
        public String keyColumns;
        /** DISTRIBUTED BY HASH 列名（逗号分隔） */
        public String distributedHashColumns;
        /** BUCKETS 数量 */
        public Integer buckets;
        /** PROPERTIES 所有键值对 */
        public Map<String, String> properties = new LinkedHashMap<>();
        /** 副本数（来自 PROPERTIES 'replication_num'） */
        public String replicationNum;
        /** 存储介质（来自 PROPERTIES 'storage_medium'）：SSD / HDD */
        public String storageMedium;

        // ===== ClickHouse 属性 =====
        /** 引擎参数（MergeTree 括号内的参数） */
        public String engineParams;
        /** ORDER BY 列（逗号分隔） */
        public String orderByColumns;
        /** PARTITION BY 表达式 */
        public String partitionByExpr;
        /** PRIMARY KEY 列（逗号分隔） */
        public String primaryKeyColumns;
        /** SAMPLE BY 列 */
        public String sampleByColumn;
        /** TTL 表达式 */
        public String ttl;
        /** SETTINGS 设置 */
        public String settings;
    }

    /**
     * Hive表级属性（向后兼容别名）。
     * @deprecated 使用 {@link TableAttributes} 代替
     */
    @Deprecated
    public static class HiveTableAttributes extends TableAttributes {
    }
}