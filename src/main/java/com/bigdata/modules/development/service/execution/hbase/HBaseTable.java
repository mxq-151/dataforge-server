package com.bigdata.modules.development.service.execution.hbase;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.hadoop.hbase.client.Connection;

import java.util.ArrayList;
import java.util.List;

public class HBaseTable extends AbstractTable implements ScannableTable {

    private final Connection connection;
    private final HBaseTableMeta tableMeta;
    private final RelProtoDataType protoRowType;
    private final List<HBaseRowkeyCondition> rowkeyConditions;

    public HBaseTable(Connection connection, HBaseTableMeta tableMeta) {
        this(connection, tableMeta, new ArrayList<>());
    }

    public HBaseTable(Connection connection, HBaseTableMeta tableMeta,
                      List<HBaseRowkeyCondition> rowkeyConditions) {
        this.connection = connection;
        this.tableMeta = tableMeta;
        this.protoRowType = createProtoRowType(tableMeta);
        this.rowkeyConditions = rowkeyConditions != null ? rowkeyConditions : new ArrayList<>();
    }

    private static RelProtoDataType createProtoRowType(HBaseTableMeta meta) {
        return (RelDataTypeFactory typeFactory) -> {
            RelDataTypeFactory.Builder builder = typeFactory.builder();

            builder.add("ROWKEY", typeFactory.createSqlType(SqlTypeName.VARCHAR, Integer.MAX_VALUE));

            if (meta.getColumnNames() != null && !meta.getColumnNames().isEmpty()) {
                for (int i = 0; i < meta.getColumnNames().size(); i++) {
                    String colName = meta.getColumnNames().get(i);
                    String colType = i < meta.getColumnTypes().size() ? meta.getColumnTypes().get(i) : "VARCHAR";
                    String normalizedName = normalizeColumnName(meta, colName);
                    builder.add(normalizedName, parseSqlType(typeFactory, colType)).nullable(true);
                }
            } else if (meta.getColumnFamilies() != null) {
                for (String cf : meta.getColumnFamilies()) {
                    builder.add(cf + ".col", typeFactory.createSqlType(SqlTypeName.VARCHAR, Integer.MAX_VALUE)).nullable(true);
                }
            }

            return builder.build();
        };
    }

    private static String normalizeColumnName(HBaseTableMeta meta, String colName) {
        if (colName.contains(".")) {
            return colName;
        }
        if (meta.getColumnFamilies() != null && !meta.getColumnFamilies().isEmpty()) {
            return meta.getColumnFamilies().get(0) + "." + colName;
        }
        return colName;
    }

    private static RelDataType parseSqlType(RelDataTypeFactory typeFactory, String typeName) {
        String upper = typeName.toUpperCase();
        switch (upper) {
            case "INT":
            case "INTEGER":
                return typeFactory.createSqlType(SqlTypeName.INTEGER);
            case "BIGINT":
                return typeFactory.createSqlType(SqlTypeName.BIGINT);
            case "SMALLINT":
                return typeFactory.createSqlType(SqlTypeName.SMALLINT);
            case "TINYINT":
                return typeFactory.createSqlType(SqlTypeName.TINYINT);
            case "DOUBLE":
                return typeFactory.createSqlType(SqlTypeName.DOUBLE);
            case "FLOAT":
                return typeFactory.createSqlType(SqlTypeName.FLOAT);
            case "DECIMAL":
                return typeFactory.createSqlType(SqlTypeName.DECIMAL);
            case "BOOLEAN":
                return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
            case "DATE":
                return typeFactory.createSqlType(SqlTypeName.DATE);
            case "TIMESTAMP":
                return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
            default:
                return typeFactory.createSqlType(SqlTypeName.VARCHAR, Integer.MAX_VALUE);
        }
    }

    public HBaseTableMeta getTableMeta() {
        return tableMeta;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return protoRowType.apply(typeFactory);
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        List<HBaseRowkeyCondition> conditions = extractRowkeyConditions(root);
        final List<HBaseRowkeyCondition> effectiveConditions =
                conditions.isEmpty() ? this.rowkeyConditions : conditions;

        return new AbstractEnumerable<Object[]>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                RelDataType rowType = getRowType((org.apache.calcite.adapter.java.JavaTypeFactory) root.getTypeFactory());
                return new HBaseEnumerator(connection, tableMeta.getTableName(),
                        tableMeta.getColumnFamilies(), rowType,
                        effectiveConditions);
            }
        };
    }

    private List<HBaseRowkeyCondition> extractRowkeyConditions(DataContext root) {
        List<HBaseRowkeyCondition> conditions = new ArrayList<>();
        try {
            Object filtersObj = root.get("hbaseRowkeyFilters");
            if (filtersObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<HBaseRowkeyCondition> list = (List<HBaseRowkeyCondition>) filtersObj;
                conditions.addAll(list);
            }
        } catch (Exception ignored) {
        }
        return conditions;
    }
}