package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.DdlExecutionLog;
import com.bigdata.modules.devops.enums.StorageEngineType;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.schema.CreateSchema;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Service
public class DdlParseService {

    private static final Logger log = LoggerFactory.getLogger(DdlParseService.class);

    @Autowired
    private MetadataSyncService metadataSyncService;

    @Autowired
    private DdlExecutionLogService ddlExecutionLogService;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Transactional
    public List<DdlExecutionLog> parseAndExecuteDdlBatch(Long datasourceId, String databaseName,
                                                          String sql, Long executeBy, String executeByName) {
        List<String> statements = splitSqlStatements(sql);
        List<DdlExecutionLog> results = new ArrayList<>();

        for (String singleSql : statements) {
            String trimmedSql = singleSql.trim();
            if (trimmedSql.isEmpty()) continue;

            try {
                DdlExecutionLog ddlLog = parseAndExecuteSingleDdl(datasourceId, databaseName, trimmedSql,
                        executeBy, executeByName);
                results.add(ddlLog);
            } catch (Exception e) {
                log.error("DDL语句执行失败: sql={}, error={}", trimmedSql, e.getMessage());
                DdlExecutionLog failedLog = ddlExecutionLogService.createLog(
                        datasourceId, databaseName, trimmedSql, detectSqlType(trimmedSql),
                        detectObjectType(trimmedSql), "unknown",
                        executeBy, executeByName
                );
                failedLog.setStatus("failed");
                failedLog.setErrorMessage(e.getMessage());
                ddlExecutionLogService.updateLog(failedLog);
                results.add(failedLog);
            }
        }

        return results;
    }

    @Transactional
    public DdlExecutionLog parseAndExecuteDdl(Long datasourceId, String databaseName,
                                               String sql, Long executeBy, String executeByName) {
        return parseAndExecuteSingleDdl(datasourceId, databaseName, sql.trim(), executeBy, executeByName);
    }

    private DdlExecutionLog parseAndExecuteSingleDdl(Long datasourceId, String databaseName,
                                                      String sql, Long executeBy, String executeByName) {
        String sqlType = detectSqlType(sql);
        String objectType = detectObjectType(sql);

        ParsedTableInfo tableInfo = parseTableName(sql);

        DdlExecutionLog ddlLog = ddlExecutionLogService.createLog(
                datasourceId, databaseName, sql, sqlType, objectType,
                tableInfo != null ? tableInfo.getFullObjectName() : "unknown",
                executeBy, executeByName
        );

        try {
            ddlLog.setStatus("running");
            ddlExecutionLogService.updateLog(ddlLog);

            long startMs = System.currentTimeMillis();

            executeDdlOnRemote(datasourceId, databaseName, sql);
            String dbName = tableInfo.databaseName;
            if(dbName==null){
                dbName=databaseName;
            }
 
            if (tableInfo != null && !"DATABASE".equals(objectType)) {
                syncMetadataAfterDdl(datasourceId, dbName, sql, sqlType, objectType, tableInfo);
            }

            ddlLog.setStatus("success");
            ddlLog.setExecutionTimeMs((int) (System.currentTimeMillis() - startMs));
            ddlLog.setMetadataUpdated(tableInfo != null ? 1 : 0);
            ddlExecutionLogService.updateLog(ddlLog);

        } catch (Exception e) {
            log.error("DDL执行失败: datasourceId={}, sql={}, error={}", datasourceId, sql, e.getMessage());
            ddlLog.setStatus("failed");
            ddlLog.setErrorMessage(e.getMessage());
            ddlExecutionLogService.updateLog(ddlLog);
        }

        return ddlLog;
    }

    private void syncMetadataAfterDdl(Long datasourceId, String databaseName, String sql,
                                       String sqlType, String objectType, ParsedTableInfo tableInfo) {
        if ("DATABASE".equals(objectType)) return;

        DatasourceConfig dsConfig = this.datasourceConfigMapper.selectById(datasourceId);
        if (dsConfig == null) return;

        StorageEngineType storageEngine = dsConfig.getStorageEngine();
        String dbName = tableInfo.getDatabaseName() != null ? tableInfo.getDatabaseName() : databaseName;
        String tableName = tableInfo.getTableName();

        switch (sqlType.toUpperCase()) {
            case "CREATE":
            case "ALTER":
                boolean synced = metadataSyncService.syncSingleTableMetadata(
                        datasourceId, storageEngine, dbName, tableName, sqlType
                );
                if (!synced) {
                    log.warn("DDL执行成功但元数据增量同步失败: {} {}.{}", sqlType, dbName, tableName);
                }
                break;
            case "DROP":
            case "TRUNCATE":
                metadataSyncService.syncSingleTableMetadata(
                        datasourceId, storageEngine, dbName, tableName, "DROP"
                );
                break;
            default:
                break;
        }
    }

    private ParsedTableInfo parseTableName(String sql) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            TableExtractorVisitor visitor = new TableExtractorVisitor();
            stmt.accept(visitor);
            return visitor.getTableInfo();
        } catch (Exception e) {
            log.warn("SQL解析失败（将使用回退方案）: sql={}, error={}", sql.substring(0, Math.min(sql.length(), 100)), e.getMessage());
            return fallbackParseTableName(sql);
        }
    }

    private ParsedTableInfo fallbackParseTableName(String sql) {
        String upperSql = sql.toUpperCase().trim();

        if (upperSql.startsWith("CREATE") || upperSql.startsWith("DROP")) {
            String[] parts = sql.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].toUpperCase();
                if ("TABLE".equals(part) || "VIEW".equals(part)) {
                    if (i + 1 < parts.length) {
                        String nextPart = parts[i + 1];
                        if ("IF".equalsIgnoreCase(nextPart)) continue;
                        if ("NOT".equalsIgnoreCase(nextPart)) continue;
                        if ("EXISTS".equalsIgnoreCase(nextPart)) continue;

                        String fullTableName = nextPart.replace("`", "").replace("\"", "");
                        int dotIndex = fullTableName.indexOf('.');
                        if (dotIndex > 0) {
                            return new ParsedTableInfo(
                                    fullTableName.substring(0, dotIndex),
                                    fullTableName.substring(dotIndex + 1)
                            );
                        } else {
                            return new ParsedTableInfo(null, fullTableName);
                        }
                    }
                }
            }
        }

        if (upperSql.startsWith("ALTER") || upperSql.startsWith("TRUNCATE")) {
            String[] parts = sql.split("\\s+");
            for (String part : parts) {
                String cleanPart = part.replace("`", "").replace("\"", "");
                if (cleanPart.contains(".")) {
                    String[] dbTable = cleanPart.split("\\.");
                    if (dbTable.length == 2 && !isKeyword(dbTable[0]) && !isKeyword(dbTable[1])) {
                        return new ParsedTableInfo(dbTable[0], dbTable[1]);
                    }
                }
            }
        }

        return null;
    }

    private boolean isKeyword(String word) {
        String upper = word.toUpperCase();
        return "TABLE".equals(upper) || "ALTER".equals(upper) || "ADD".equals(upper) ||
               "DROP".equals(upper) || "COLUMN".equals(upper) || "MODIFY".equals(upper) ||
               "CHANGE".equals(upper) || "RENAME".equals(upper) || "IF".equals(upper) ||
               "NOT".equals(upper) || "EXISTS".equals(upper) || "EXTERNAL".equals(upper) ||
               "TEMPORARY".equals(upper);
    }

    private List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = ' ';
        boolean inComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (inComment) {
                current.append(c);
                if (c == '\n') {
                    inComment = false;
                }
                continue;
            }

            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inComment = true;
                current.append(c);
                continue;
            }

            if ((c == '\'' || c == '"' || c == '`') && !inQuote) {
                inQuote = true;
                quoteChar = c;
                current.append(c);
                continue;
            }

            if (c == quoteChar && inQuote) {
                inQuote = false;
                current.append(c);
                continue;
            }

            if (c == ';' && !inQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current = new StringBuilder();
                continue;
            }

            current.append(c);
        }

        String lastStatement = current.toString().trim();
        if (!lastStatement.isEmpty()) {
            statements.add(lastStatement);
        }

        return statements;
    }

    private void executeDdlOnRemote(Long datasourceId, String databaseName, String sql) throws SQLException {
        DatasourceConfig dsConfig = datasourceConfigMapper.selectById(datasourceId);
        if (dsConfig == null) {
            throw new SQLException("数据源不存在: id=" + datasourceId);
        }

        Connection conn = null;
        Statement stmt = null;
        try {
            conn = datasourceConfigService.getConnection(datasourceId, databaseName);
            stmt = conn.createStatement();
            stmt.execute(sql);
        } catch (SQLException e) {
            log.error("DDL执行失败: datasourceId={}, sql={}, error={}", datasourceId, sql, e.getMessage());
            throw e;
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException ignored) {}
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private String detectSqlType(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("CREATE")) return "CREATE";
        if (upper.startsWith("ALTER")) return "ALTER";
        if (upper.startsWith("DROP")) return "DROP";
        if (upper.startsWith("TRUNCATE")) return "TRUNCATE";
        return "UNKNOWN";
    }

    private String detectObjectType(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.contains(" TABLE ")) return "TABLE";
        if (upper.contains(" VIEW ")) return "VIEW";
        if (upper.contains(" INDEX ")) return "INDEX";
        if (upper.contains(" DATABASE ") || upper.contains(" SCHEMA ")) return "DATABASE";
        return "TABLE";
    }

    private static class TableExtractorVisitor extends StatementVisitorAdapter {
        private ParsedTableInfo tableInfo;

        @Override
        public void visit(CreateTable createTable) {
            Table table = createTable.getTable();
            this.tableInfo = extractTableInfo(table);
        }

        @Override
        public void visit(Alter alter) {
            Table table = alter.getTable();
            this.tableInfo = extractTableInfo(table);
        }

        @Override
        public void visit(Drop drop) {
            try {
                Table table = drop.getName();
                if (table != null && table.getName() != null) {
                    String tableName = table.getName().toLowerCase();
                    if (!tableName.equalsIgnoreCase("database") &&
                        !tableName.equalsIgnoreCase("schema")) {
                        this.tableInfo = extractTableInfo(table);
                    }
                }
            } catch (Exception e) {
                log.debug("无法从Drop语句提取表信息: {}", e.getMessage());
            }
        }

        @Override
        public void visit(Truncate truncate) {
            Table table = truncate.getTable();
            this.tableInfo = extractTableInfo(table);
        }

        @Override
        public void visit(CreateSchema createSchema) {
            this.tableInfo = null;
        }

        private ParsedTableInfo extractTableInfo(Table table) {
            if (table == null) return null;

            String schemaName = stripQuotes(table.getSchemaName());
            String tableName = stripQuotes(table.getName());

            if (schemaName != null && !schemaName.isEmpty()) {
                return new ParsedTableInfo(schemaName, tableName);
            } else {
                return new ParsedTableInfo(null, tableName);
            }
        }

        private String stripQuotes(String value) {
            if (value == null) return null;
            if ((value.startsWith("`") && value.endsWith("`")) ||
                (value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        public ParsedTableInfo getTableInfo() {
            return tableInfo;
        }
    }

    static class ParsedTableInfo {
        private final String databaseName;
        private final String tableName;

        public ParsedTableInfo(String databaseName, String tableName) {
            this.databaseName = databaseName;
            this.tableName = tableName;
        }

        public String getDatabaseName() { return databaseName; }
        public String getTableName() { return tableName; }

        public String getFullObjectName() {
            if (databaseName != null) {
                return databaseName + "." + tableName;
            }
            return tableName;
        }
    }
}
