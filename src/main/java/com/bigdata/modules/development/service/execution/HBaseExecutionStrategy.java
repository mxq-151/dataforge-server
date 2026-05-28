package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.development.service.execution.hbase.HBaseCalciteService;
import com.bigdata.modules.development.service.execution.hbase.HBaseRowkeyCondition;
import com.bigdata.modules.development.service.execution.hbase.HBaseRowkeyFilterExtractor;
import com.bigdata.modules.development.service.execution.hbase.HBaseSchema;
import com.bigdata.modules.development.service.execution.hbase.HBaseTableMeta;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HBaseExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(HBaseExecutionStrategy.class);
    private static final int MAX_RESULT_ROWS = 10000;
    private static final String DEFAULT_COLUMN_FAMILY = "info";

    private final ConcurrentHashMap<String, Connection> connectionCache = new ConcurrentHashMap<>();

    @Autowired
    private TableMetadataService tableMetadataService;

    @Override
    public EngineType getEngineType() {
        return EngineType.HBASE;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return codeType == CodeType.SQL;
    }

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        DatasourceConfig datasourceConfig = context.getPrimaryDatasourceConfig();
        if (datasourceConfig == null) {
            return CodeExecutionResult.failure(executionId, "HBase执行引擎需要指定数据源");
        }

        Connection hbaseConnection = null;
        try {
            hbaseConnection = getOrCreateConnection(datasourceConfig);
            HBaseCalciteService calciteService = new HBaseCalciteService(hbaseConnection);
            HBaseSchema schema = new HBaseSchema(hbaseConnection, tableMetadataService, datasourceConfig.getId());
            schema.loadMetadataFromDb();

            String sql = context.getCodeContent();
            log.info("HBase执行SQL: {}", sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

            List<String> sqlStatements = splitSqlStatements(sql);
            log.info("拆分出{}条SQL语句", sqlStatements.size());

            List<CodeExecutionResult.SqlResultItem> results = new ArrayList<>();
            List<String> lastQueryColumns = null;
            List<List<String>> lastQueryRows = null;
            int totalAffectedRows = 0;

            for (int i = 0; i < sqlStatements.size(); i++) {
                String singleSql = sqlStatements.get(i).trim();
                log.info("执行第{}/{}条SQL: {}", i + 1, sqlStatements.size(),
                        singleSql.length() > 100 ? singleSql.substring(0, 100) + "..." : singleSql);

                try {
                    String sqlUpper = singleSql.toUpperCase();

                    if (sqlUpper.startsWith("CREATE TABLE")) {
                        CodeExecutionResult.SqlResultItem result = handleCreateTable(
                                singleSql, calciteService, schema, datasourceConfig, i);
                        results.add(result);
                        if (!result.isSuccess()) {
                            break;
                        }
                    } else if (sqlUpper.startsWith("SELECT")) {
                        CodeExecutionResult.SqlResultItem result = handleSelect(
                                singleSql, calciteService, schema, i);
                        results.add(result);
                        if (result.isSuccess()) {
                            lastQueryColumns = result.getColumns();
                            lastQueryRows = result.getRows();
                            totalAffectedRows += result.getAffectedRows();
                        } else {
                            break;
                        }
                    } else if (sqlUpper.startsWith("SHOW TABLES") || sqlUpper.startsWith("LIST TABLES")) {
                        CodeExecutionResult.SqlResultItem result = handleShowTables(schema, i);
                        results.add(result);
                        if (result.isSuccess()) {
                            lastQueryColumns = result.getColumns();
                            lastQueryRows = result.getRows();
                        }
                    } else if (sqlUpper.startsWith("DESC") || sqlUpper.startsWith("DESCRIBE")) {
                        CodeExecutionResult.SqlResultItem result = handleDescribe(
                                singleSql, schema, i);
                        results.add(result);
                        if (result.isSuccess()) {
                            lastQueryColumns = result.getColumns();
                            lastQueryRows = result.getRows();
                        }
                    } else {
                        results.add(CodeExecutionResult.SqlResultItem.failure(i, singleSql,
                                "不支持的SQL类型，HBase仅支持: SELECT, CREATE TABLE, SHOW TABLES, DESC"));
                        break;
                    }
                } catch (Exception e) {
                    log.error("第{}条SQL执行失败: {}", i + 1, e.getMessage());
                    results.add(CodeExecutionResult.SqlResultItem.failure(i, singleSql, e.getMessage()));
                    break;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            boolean allSuccess = results.stream().allMatch(CodeExecutionResult.SqlResultItem::isSuccess);

            CodeExecutionResult result;
            if (allSuccess) {
                String outputLog = sqlStatements.size() == 1
                        ? results.get(0).getMessage()
                        : "共" + sqlStatements.size() + "条SQL全部执行成功";
                result = CodeExecutionResult.success(executionId, outputLog,
                        lastQueryColumns, lastQueryRows, totalAffectedRows);
            } else {
                result = CodeExecutionResult.failure(executionId,
                        "第" + results.size() + "条SQL执行失败: "
                                + results.get(results.size() - 1).getMessage());
                result.setColumns(lastQueryColumns);
                result.setRows(lastQueryRows);
                result.setAffectedRows(totalAffectedRows);
            }
            result.setResults(results);
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;

        } catch (Exception e) {
            log.error("HBase执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId,
                    "HBase执行异常: " + e.getMessage());
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    private CodeExecutionResult.SqlResultItem handleCreateTable(String sql,
                                                                 HBaseCalciteService calciteService,
                                                                 HBaseSchema schema,
                                                                 DatasourceConfig datasourceConfig,
                                                                 int index) throws Exception {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof CreateTable)) {
                return CodeExecutionResult.SqlResultItem.failure(index, sql, "无法解析CREATE TABLE语句");
            }

            CreateTable createTable = (CreateTable) stmt;
            String tableName = createTable.getTable().getName();

            if (createTable.getColumnDefinitions() == null || createTable.getColumnDefinitions().isEmpty()) {
                return CodeExecutionResult.SqlResultItem.failure(index, sql,
                        "HBase表必须包含至少一个列定义，且必须有rowkey字段");
            }

            boolean hasRowkey = false;
            for (ColumnDefinition colDef : createTable.getColumnDefinitions()) {
                if ("rowkey".equalsIgnoreCase(colDef.getColumnName())) {
                    hasRowkey = true;
                    break;
                }
            }
            if (!hasRowkey) {
                return CodeExecutionResult.SqlResultItem.failure(index, sql,
                        "HBase表必须包含一个rowkey字段作为主键，例如: CREATE TABLE xxx (rowkey VARCHAR, name VARCHAR)");
            }

            boolean created = calciteService.createTable(tableName);

            HBaseTableMeta meta = new HBaseTableMeta();
            meta.setTableName(tableName);
            meta.getColumnFamilies().add(DEFAULT_COLUMN_FAMILY);

            if (createTable.getColumnDefinitions() != null) {
                for (ColumnDefinition colDef : createTable.getColumnDefinitions()) {
                    String colName = colDef.getColumnName();
                    String colType = colDef.getColDataType().getDataType();

                    if ("rowkey".equalsIgnoreCase(colName)) {
                        meta.addColumn(colName, colType);
                    } else if (colName.contains(".")) {
                        meta.addColumn(colName, colType);
                    } else {
                        meta.addColumn(DEFAULT_COLUMN_FAMILY + "." + colName, colType);
                    }
                }
            }

            schema.registerTable(tableName, meta);
            syncCreateTableToMetadata(datasourceConfig, tableName, createTable);

            return CodeExecutionResult.SqlResultItem.dmlSuccess(index, sql, created ? 1 : 0);

        } catch (Exception e) {
            log.error("CREATE TABLE执行失败", e);
            return CodeExecutionResult.SqlResultItem.failure(index, sql, e.getMessage());
        }
    }

    private void syncCreateTableToMetadata(DatasourceConfig datasourceConfig, String tableName,
                                            CreateTable createTable) {
        try {
            TableMetadata tableMeta = new TableMetadata();
            tableMeta.setDatasourceId(datasourceConfig.getId());
            tableMeta.setStorageEngine(StorageEngineType.HBASE);
            tableMeta.setDatabaseName(datasourceConfig.getDatabaseName() != null && !datasourceConfig.getDatabaseName().isEmpty()
                    ? datasourceConfig.getDatabaseName().get(0) : "default");
            tableMeta.setTableName(tableName);
            tableMeta.setTableType("TABLE");
            tableMeta.setSyncTime(new Date());

            List<ColumnMetadata> columns = new ArrayList<>();

            ColumnMetadata rowkeyCol = new ColumnMetadata();
            rowkeyCol.setColumnName("ROWKEY");
            rowkeyCol.setColumnType("VARCHAR");
            rowkeyCol.setIsPrimaryKey(1);
            rowkeyCol.setOrdinalPosition(1);
            rowkeyCol.setNullable(0);
            columns.add(rowkeyCol);

            if (createTable.getColumnDefinitions() != null) {
                int pos = 2;
                for (ColumnDefinition colDef : createTable.getColumnDefinitions()) {
                    if ("rowkey".equalsIgnoreCase(colDef.getColumnName())) {
                        continue;
                    }
                    ColumnMetadata colMeta = new ColumnMetadata();
                    String colName = colDef.getColumnName();
                    if (!colName.contains(".")) {
                        colName = DEFAULT_COLUMN_FAMILY + "." + colName;
                    }
                    colMeta.setColumnName(colName);
                    colMeta.setColumnType(colDef.getColDataType().getDataType());
                    colMeta.setOrdinalPosition(pos++);
                    colMeta.setNullable(1);
                    columns.add(colMeta);
                }
            }

            tableMetadataService.syncSingleTable(StorageEngineType.HBASE, tableMeta, columns);
            log.info("CREATE TABLE元数据同步成功: {}", tableName);
        } catch (Exception e) {
            log.error("CREATE TABLE元数据同步失败: {}", tableName, e);
        }
    }

    private CodeExecutionResult.SqlResultItem handleSelect(String sql,
                                                            HBaseCalciteService calciteService,
                                                            HBaseSchema schema,
                                                            int index) throws Exception {
        try {
            String rowkeyFilterError = validateRowkeyFilter(sql);
            if (rowkeyFilterError != null) {
                return CodeExecutionResult.SqlResultItem.failure(index, sql, rowkeyFilterError);
            }

            List<HBaseRowkeyCondition> rowkeyConditions = HBaseRowkeyFilterExtractor.extract(sql);
            log.info("提取到{}个rowkey下推条件", rowkeyConditions.size());

            HBaseCalciteService.QueryResult queryResult =
                    calciteService.executeQuery(sql, schema, rowkeyConditions);

            List<List<String>> rows = new ArrayList<>();
            int count = 0;
            for (List<String> row : queryResult.rows) {
                if (count >= MAX_RESULT_ROWS) {
                    break;
                }
                rows.add(row);
                count++;
            }

            return CodeExecutionResult.SqlResultItem.querySuccess(index, sql,
                    queryResult.columns, rows, count);
        } catch (Exception e) {
            log.error("SELECT执行失败", e);
            return CodeExecutionResult.SqlResultItem.failure(index, sql, e.getMessage());
        }
    }

    private String validateRowkeyFilter(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return "HBase SELECT语句解析失败";
            }
            Select select = (Select) stmt;
            if (select.getSelectBody() instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                if (plainSelect.getWhere() == null) {
                    return "HBase查询必须包含WHERE条件过滤rowkey，例如: SELECT * FROM table WHERE rowkey = 'xxx'";
                }

                String whereStr = plainSelect.getWhere().toString();
                if (!whereStr.toUpperCase().contains("ROWKEY")) {
                    return "HBase查询的WHERE条件中必须包含rowkey过滤条件，" +
                            "例如: WHERE rowkey = 'xxx' 或 WHERE rowkey IN ('a','b') 或 WHERE rowkey LIKE 'prefix*'";
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("SELECT语句rowkey校验异常，放行执行: {}", e.getMessage());
            return null;
        }
    }

    private CodeExecutionResult.SqlResultItem handleShowTables(HBaseSchema schema, int index) {
        try {
            schema.loadMetadataFromDb();
            List<String> tableNames = new ArrayList<>();
            for (String tableName : schema.getTableNames()) {
                tableNames.add(tableName);
            }

            List<String> columns = Arrays.asList("TABLE_NAME");
            List<List<String>> rows = new ArrayList<>();
            for (String name : tableNames) {
                rows.add(Arrays.asList(name));
            }

            return CodeExecutionResult.SqlResultItem.querySuccess(index, "SHOW TABLES",
                    columns, rows, rows.size());
        } catch (Exception e) {
            log.error("SHOW TABLES执行失败", e);
            return CodeExecutionResult.SqlResultItem.failure(index, "SHOW TABLES", e.getMessage());
        }
    }

    private CodeExecutionResult.SqlResultItem handleDescribe(String sql, HBaseSchema schema, int index) {
        try {
            String sqlUpper = sql.toUpperCase().trim();
            String tableName = null;
            if (sqlUpper.startsWith("DESC ")) {
                tableName = sqlUpper.substring(5).trim().replace(";", "");
            } else if (sqlUpper.startsWith("DESCRIBE ")) {
                tableName = sqlUpper.substring(9).trim().replace(";", "");
            }

            if (tableName == null) {
                return CodeExecutionResult.SqlResultItem.failure(index, sql, "无法解析表名");
            }

            List<String> columns = Arrays.asList("COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("ROWKEY", "VARCHAR", "NO"));

            org.apache.calcite.schema.Table calciteTable = schema.getTable(tableName.toUpperCase());
            if (calciteTable instanceof com.bigdata.modules.development.service.execution.hbase.HBaseTable) {
                com.bigdata.modules.development.service.execution.hbase.HBaseTable hbaseTable =
                        (com.bigdata.modules.development.service.execution.hbase.HBaseTable) calciteTable;
                HBaseTableMeta meta = hbaseTable.getTableMeta();

                if (meta.getColumnNames() != null && !meta.getColumnNames().isEmpty()) {
                    for (int i = 0; i < meta.getColumnNames().size(); i++) {
                        String colName = meta.getColumnNames().get(i);
                        if ("ROWKEY".equalsIgnoreCase(colName)) {
                            continue;
                        }
                        String colType = i < meta.getColumnTypes().size()
                                ? meta.getColumnTypes().get(i) : "VARCHAR";
                        rows.add(Arrays.asList(colName, colType, "YES"));
                    }
                }
            }

            return CodeExecutionResult.SqlResultItem.querySuccess(index, sql, columns, rows, rows.size());
        } catch (Exception e) {
            log.error("DESCRIBE执行失败", e);
            return CodeExecutionResult.SqlResultItem.failure(index, sql, e.getMessage());
        }
    }

    private Connection getOrCreateConnection(DatasourceConfig config) throws Exception {
        String cacheKey = config.getId() + "_" + config.getUrl();
        Connection conn = connectionCache.get(cacheKey);
        if (conn == null || conn.isClosed()) {
            Configuration hbaseConf = HBaseConfiguration.create();

            String zkQuorum = config.getUrl();
            if (zkQuorum != null && !zkQuorum.isEmpty()) {
                hbaseConf.set("hbase.zookeeper.quorum", zkQuorum);
            }

            hbaseConf.set("hbase.zookeeper.property.clientPort", "2181");

            String extraParams = config.getExtraParams();
            if (extraParams != null && !extraParams.isEmpty()) {
                try {
                    String[] params = extraParams.split("&");
                    for (String param : params) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            hbaseConf.set(kv[0].trim(), kv[1].trim());
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            conn = ConnectionFactory.createConnection(hbaseConf);
            connectionCache.put(cacheKey, conn);
            log.info("创建HBase连接: ZK={}", zkQuorum);
        }
        return conn;
    }

    private List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        int length = sql.length();
        int i = 0;

        while (i < length) {
            char ch = sql.charAt(i);

            if (ch == '\'') {
                current.append(ch);
                i++;
                while (i < length) {
                    char inner = sql.charAt(i);
                    current.append(inner);
                    i++;
                    if (inner == '\'') {
                        if (i < length && sql.charAt(i) == '\'') {
                            current.append(sql.charAt(i));
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '"') {
                current.append(ch);
                i++;
                while (i < length) {
                    char inner = sql.charAt(i);
                    current.append(inner);
                    i++;
                    if (inner == '"') {
                        break;
                    }
                }
            } else if (ch == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (ch == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current = new StringBuilder();
                i++;
            } else {
                current.append(ch);
                i++;
            }
        }

        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }

        return statements;
    }
}