package com.bigdata.modules.development.service.execution.hbase;

import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class HBaseEnumerator implements Enumerator<Object[]> {

    private static final Logger log = LoggerFactory.getLogger(HBaseEnumerator.class);

    private final Connection connection;
    private final String tableName;
    private final List<String> columnFamilies;
    private final RelDataType rowType;
    private final int columnCount;
    private final List<HBaseRowkeyCondition> rowkeyConditions;

    private ResultScanner scanner;
    private Iterator<Result> iterator;
    private Object[] current;

    public HBaseEnumerator(Connection connection, String tableName,
                           List<String> columnFamilies,
                           RelDataType rowType,
                           List<HBaseRowkeyCondition> rowkeyConditions) {
        this.connection = connection;
        this.tableName = tableName;
        this.columnFamilies = columnFamilies != null ? columnFamilies : new ArrayList<>();
        this.rowType = rowType;
        this.columnCount = rowType.getFieldCount();
        this.rowkeyConditions = rowkeyConditions != null ? rowkeyConditions : new ArrayList<>();
    }

    @Override
    public Object[] current() {
        return current;
    }

    @Override
    public boolean moveNext() {
        if (scanner == null) {
            try {
                scanner = createScanner();
                iterator = scanner.iterator();
            } catch (Exception e) {
                log.error("创建HBase Scanner失败: {}", tableName, e);
                return false;
            }
        }

        if (iterator.hasNext()) {
            Result result = iterator.next();
            current = convertRow(result);
            return true;
        }
        return false;
    }

    @Override
    public void reset() {
        closeScanner();
        scanner = null;
        iterator = null;
    }

    @Override
    public void close() {
        closeScanner();
    }

    private void closeScanner() {
        if (scanner != null) {
            try {
                scanner.close();
            } catch (Exception ignored) {
            }
            scanner = null;
        }
    }

    private ResultScanner createScanner() throws Exception {
        Table table = connection.getTable(TableName.valueOf(tableName));
        Scan scan = new Scan();

        if (!columnFamilies.isEmpty()) {
            for (String cf : columnFamilies) {
                scan.addFamily(Bytes.toBytes(cf));
            }
        }

        applyRowkeyFilters(scan);

        log.info("创建HBase Scan: table={}, rowkeyFilters={}", tableName, rowkeyConditions.size());
        return table.getScanner(scan);
    }

    private void applyRowkeyFilters(Scan scan) {
        if (rowkeyConditions.isEmpty()) {
            return;
        }

        FilterList allFilters = new FilterList(FilterList.Operator.MUST_PASS_ALL);

        for (HBaseRowkeyCondition condition : rowkeyConditions) {
            switch (condition.opType) {
                case "BETWEEN":
                    applyBetween(scan, condition);
                    break;
                case "PREFIX":
                    allFilters.addFilter(new PrefixFilter(condition.comparator.getValue()));
                    break;
                case "IN":
                    allFilters.addFilter(buildInFilter(condition));
                    break;
                case "LIKE_REGEX":
                    allFilters.addFilter(new RowFilter(CompareFilter.CompareOp.EQUAL,
                            condition.comparator));
                    break;
                default:
                    allFilters.addFilter(new RowFilter(
                            condition.compareOp, condition.comparator));
                    break;
            }
        }

        if (!allFilters.getFilters().isEmpty()) {
            scan.setFilter(allFilters);
        }
    }

    private void applyBetween(Scan scan, HBaseRowkeyCondition condition) {
        if (condition.startRow != null && condition.stopRow != null) {
            byte[] start = Bytes.add(condition.startRow, new byte[]{0x00});
            scan.withStartRow(start);
            scan.withStopRow(condition.stopRow);
        } else if (condition.startRow != null) {
            scan.withStartRow(condition.startRow);
        } else if (condition.stopRow != null) {
            scan.withStopRow(condition.stopRow);
        }
    }

    private Filter buildInFilter(HBaseRowkeyCondition condition) {
        if (condition.inValues == null || condition.inValues.length == 0) {
            return new RowFilter(CompareFilter.CompareOp.NO_OP, new BinaryComparator(new byte[0]));
        }

        if (condition.inValues.length == 1) {
            return new RowFilter(CompareFilter.CompareOp.EQUAL,
                    new BinaryComparator(condition.inValues[0]));
        }

        FilterList inList = new FilterList(FilterList.Operator.MUST_PASS_ONE);
        for (byte[] value : condition.inValues) {
            inList.addFilter(new RowFilter(CompareFilter.CompareOp.EQUAL,
                    new BinaryComparator(value)));
        }
        return inList;
    }

    private Object[] convertRow(Result result) {
        Object[] row = new Object[columnCount];

        Map<String, RelDataType> fieldTypes = new java.util.HashMap<>();
        for (int i = 0; i < columnCount; i++) {
            fieldTypes.put(rowType.getFieldList().get(i).getName(), rowType.getFieldList().get(i).getType());
        }

        for (int i = 0; i < columnCount; i++) {
            String fieldName = rowType.getFieldList().get(i).getName();
            RelDataType fieldType = rowType.getFieldList().get(i).getType();

            if ("ROWKEY".equalsIgnoreCase(fieldName)) {
                row[i] = Bytes.toString(result.getRow());
                continue;
            }

            String[] parts = parseHBaseColumn(fieldName);
            if (parts != null && parts.length == 2) {
                byte[] value = result.getValue(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
                row[i] = convertValue(value, fieldType);
            } else {
                row[i] = null;
            }
        }

        return row;
    }

    private String[] parseHBaseColumn(String fieldName) {
        for (String cf : columnFamilies) {
            if (fieldName.startsWith(cf + ".")) {
                String qualifier = fieldName.substring(cf.length() + 1);
                return new String[]{cf, qualifier};
            }
        }

        int dotIndex = fieldName.indexOf('.');
        if (dotIndex > 0) {
            return new String[]{fieldName.substring(0, dotIndex), fieldName.substring(dotIndex + 1)};
        }
        return null;
    }

    private Object convertValue(byte[] value, RelDataType fieldType) {
        if (value == null) {
            return null;
        }
        String typeName = fieldType.getSqlTypeName().name();
        String strVal = Bytes.toString(value);
        try {
            if (typeName.contains("INT") || typeName.contains("BIGINT") || typeName.contains("TINYINT") || typeName.contains("SMALLINT")) {
                return Long.parseLong(strVal.trim());
            }
            if (typeName.contains("DOUBLE") || typeName.contains("FLOAT") || typeName.contains("DECIMAL")) {
                return Double.parseDouble(strVal.trim());
            }
            if (typeName.contains("BOOLEAN")) {
                return Boolean.parseBoolean(strVal.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return strVal;
    }
}