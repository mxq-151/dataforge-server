package com.bigdata.modules.telemetry.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataTypesTest {

    @Test
    @DisplayName("数据类型词汇与 agentkit 约定一致")
    void vocabularyMatchesContract() {
        List<String> implemented = List.of(
                DataTypes.LOG_RUNTIME, DataTypes.LOG_STDOUT, DataTypes.LOG_AUDIT,
                DataTypes.LOG_EXCEPTION_SUMMARY, DataTypes.APP_METRICS,
                DataTypes.JVM_RUNTIME, DataTypes.JVM_GC);
        List<String> declared = List.of(
                DataTypes.OS_CPU, DataTypes.OS_MEMORY, DataTypes.OS_DISK,
                DataTypes.LOG_SLOW_QUERY);

        implemented.forEach(id -> {
            assertNotNull(DataTypes.get(id), id);
            assertTrue(DataTypes.get(id).implemented(), id);
        });
        declared.forEach(id -> {
            assertNotNull(DataTypes.get(id), id);
            assertEquals(DataTypeStatus.DECLARED_NOT_IMPLEMENTED, DataTypes.get(id).status(), id);
        });
        assertEquals(11, DataTypes.all().size());
    }
}
