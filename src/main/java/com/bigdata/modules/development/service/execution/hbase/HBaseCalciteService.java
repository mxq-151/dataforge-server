package com.bigdata.modules.development.service.execution.hbase;

import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class HBaseCalciteService {

    private static final Logger log = LoggerFactory.getLogger(HBaseCalciteService.class);
    private static final String DEFAULT_COLUMN_FAMILY = "info";

    private final Connection hbaseConnection;

    public HBaseCalciteService(Connection hbaseConnection) {
        this.hbaseConnection = hbaseConnection;
    }

    public static class QueryResult {
        public List<String> columns;
        public List<List<String>> rows;
        public int rowCount;

        public QueryResult(List<String> columns, List<List<String>> rows, int rowCount) {
            this.columns = columns;
            this.rows = rows;
            this.rowCount = rowCount;
        }
    }

    public QueryResult executeQuery(String sql, HBaseSchema schema,
                                      List<HBaseRowkeyCondition> rowkeyConditions) throws Exception {
        SchemaPlus rootSchema = Frameworks.createRootSchema(true);
        rootSchema.add("HBASE", schema);

        Properties info = new Properties();
        info.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");

        FrameworkConfig frameworkConfig = Frameworks.newConfigBuilder()
                .defaultSchema(rootSchema.getSubSchema("HBASE"))
                .parserConfig(SqlParser.config().withCaseSensitive(false))
                .build();

        Planner planner = Frameworks.getPlanner(frameworkConfig);

        SqlNode sqlNode;
        try {
            sqlNode = planner.parse(sql);
        } catch (Exception e) {
            throw new RuntimeException("SQL解析失败: " + e.getMessage(), e);
        }

        SqlNode validatedSqlNode;
        try {
            validatedSqlNode = planner.validate(sqlNode);
        } catch (ValidationException e) {
            throw new RuntimeException("SQL校验失败: " + e.getMessage(), e);
        }

        RelRoot relRoot;
        try {
            relRoot = planner.rel(validatedSqlNode);
        } catch (RelConversionException e) {
            throw new RuntimeException("关系表达式转换失败: " + e.getMessage(), e);
        }

        RelNode relNode = relRoot.project();
        String plan = RelOptUtil.dumpPlan("[HBase Calcite Plan]", relNode, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES);
        log.info("执行计划:\n{}", plan);

        RelDataType rowType = relNode.getRowType();

        List<String> columns = new ArrayList<>();
        for (int i = 0; i < rowType.getFieldCount(); i++) {
            columns.add(rowType.getFieldList().get(i).getName());
        }

        List<List<String>> allRows = new ArrayList<>();
        try {
            org.apache.calcite.interpreter.Interpreter interpreter =
                    new org.apache.calcite.interpreter.Interpreter(
                            org.apache.calcite.DataContexts.EMPTY, relNode);
            try (org.apache.calcite.linq4j.Enumerator<Object[]> enumerator = interpreter.enumerator()) {
                int count = 0;
                while (enumerator.moveNext() && count < 1000) {
                    Object[] values = enumerator.current();
                    List<String> row = new ArrayList<>();
                    for (Object val : values) {
                        row.add(val != null ? val.toString() : "NULL");
                    }
                    allRows.add(row);
                    count++;
                }
                log.info("查询返回{}行数据", count);
            }
        } catch (Exception e) {
            log.warn("Interpreter执行失败, 尝试直接扫描: {}", e.getMessage());
            allRows = executeWithDirectScan(schema, sql, columns, rowType, rowkeyConditions);
        }

        return new QueryResult(columns, allRows, allRows.size());
    }

    private List<List<String>> executeWithDirectScan(HBaseSchema schema, String sql,
                                                      List<String> columns, RelDataType rowType,
                                                      List<HBaseRowkeyCondition> rowkeyConditions) throws Exception {
        List<List<String>> results = new ArrayList<>();
        String tableName = extractTableName(sql.toUpperCase().trim());
        if (tableName == null) {
            return results;
        }

        org.apache.calcite.schema.Table calciteTable = schema.getTable(tableName.toUpperCase());
        if (!(calciteTable instanceof HBaseTable)) {
            return results;
        }

        HBaseTable hbaseTable = (HBaseTable) calciteTable;
        HBaseTableMeta meta = hbaseTable.getTableMeta();

        HBaseEnumerator enumerator = new HBaseEnumerator(
                hbaseConnection, meta.getTableName(),
                meta.getColumnFamilies(), rowType,
                rowkeyConditions);

        try {
            int count = 0;
            while (enumerator.moveNext() && count < 1000) {
                Object[] values = enumerator.current();
                List<String> row = new ArrayList<>();
                for (Object val : values) {
                    row.add(val != null ? val.toString() : "NULL");
                }
                results.add(row);
                count++;
            }
        } finally {
            enumerator.close();
        }

        return results;
    }

    private String extractTableName(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        int fromIdx = s.indexOf(" FROM ");
        if (fromIdx < 0) {
            return null;
        }
        String afterFrom = s.substring(fromIdx + 6).trim();
        int spaceIdx = afterFrom.indexOf(' ');
        if (spaceIdx > 0) {
            return afterFrom.substring(0, spaceIdx);
        }
        int whereIdx = afterFrom.indexOf("WHERE");
        if (whereIdx > 0) {
            return afterFrom.substring(0, whereIdx).trim();
        }
        return afterFrom.replace(";", "").trim();
    }

    public boolean createTable(String tableName) throws Exception {
        try (Admin admin = hbaseConnection.getAdmin()) {
            TableName tn = TableName.valueOf(tableName);
            if (admin.tableExists(tn)) {
                log.warn("HBase表已存在: {}", tn);
                return false;
            }
            HTableDescriptor tableDescriptor = new HTableDescriptor(tn);
            tableDescriptor.addFamily(new HColumnDescriptor(DEFAULT_COLUMN_FAMILY));
            admin.createTable(tableDescriptor);
            log.info("HBase表创建成功: {} (列族: {})", tableName, DEFAULT_COLUMN_FAMILY);
            return true;
        }
    }

    public boolean tableExists(String tableName) throws Exception {
        try (Admin admin = hbaseConnection.getAdmin()) {
            return admin.tableExists(TableName.valueOf(tableName));
        }
    }

    public Connection getHbaseConnection() {
        return hbaseConnection;
    }
}