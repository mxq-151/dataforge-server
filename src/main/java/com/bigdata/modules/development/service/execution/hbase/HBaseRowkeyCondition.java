package com.bigdata.modules.development.service.execution.hbase;

import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter;

public class HBaseRowkeyCondition {

    public final String opType;
    public final String value;
    public final CompareFilter.CompareOp compareOp;
    public final ByteArrayComparable comparator;
    public final byte[] startRow;
    public final byte[] stopRow;
    public final byte[][] inValues;

    public HBaseRowkeyCondition(String opType, String value,
                                 CompareFilter.CompareOp compareOp,
                                 ByteArrayComparable comparator,
                                 byte[] startRow, byte[] stopRow, byte[][] inValues) {
        this.opType = opType;
        this.value = value;
        this.compareOp = compareOp;
        this.comparator = comparator;
        this.startRow = startRow;
        this.stopRow = stopRow;
        this.inValues = inValues;
    }
}