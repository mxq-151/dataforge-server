package com.bigdata.common.task;

import com.bigdata.modules.system.mapper.OperationAuditLogMapper;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审计日志分区维护任务的方言分支与异常兜底。
 *
 * <p>该任务在 MySQL/MariaDB 上执行 DDL、在 PG/H2 上必须完全跳过；DDL 失败必须被吞掉，
 * 否则会打断调度线程。这些分支此前完全没有覆盖。</p>
 */
@DisplayName("Common模块 - 审计日志分区维护任务")
class AuditLogPartitionTaskTest {

    private OperationAuditLogMapper mapper;
    private DatabaseIdProvider databaseIdProvider;
    private AuditLogPartitionTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(OperationAuditLogMapper.class);
        databaseIdProvider = mock(DatabaseIdProvider.class);
        task = new AuditLogPartitionTask();
        ReflectionTestUtils.setField(task, "auditLogMapper", mapper);
        ReflectionTestUtils.setField(task, "databaseIdProvider", databaseIdProvider);
        ReflectionTestUtils.setField(task, "dataSource", mock(DataSource.class));
    }

    @Test
    @DisplayName("PostgreSQL：创建与清理都直接跳过，不触碰任何 DDL")
    void postgresqlSkipsPartitionMaintenance() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("postgresql");

        task.createNextPartition();
        task.cleanupExpiredPartitions();

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("H2：不支持分区，同样跳过")
    void h2SkipsPartitionMaintenance() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("h2");

        task.createNextPartition();
        task.cleanupExpiredPartitions();

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("方言探测抛异常：归一为 other 并跳过，不影响调度线程")
    void dialectProbeFailureFallsBackToOther() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(task::createNextPartition);
        assertDoesNotThrow(task::cleanupExpiredPartitions);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("MySQL：无任何分区时一次性补齐未来 30 天，且从今天开始")
    void mysqlCreatesMissingPartitionsForNextThirtyDays() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mysql");
        when(mapper.selectAllPartitionNames()).thenReturn(Collections.emptyList());

        task.createNextPartition();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(1)).createPartitions(captor.capture());
        String defs = captor.getValue();
        LocalDate today = LocalDate.now();
        String expectedFirst = "PARTITION p" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertTrue(defs.startsWith(expectedFirst), "首个分区应覆盖今天，实际: " + defs.substring(0, Math.min(60, defs.length())));
        assertEquals(30, defs.split("PARTITION ").length - 1, "应补齐 30 个日分区");
    }

    @Test
    @DisplayName("MySQL：已存在的分区不重复创建")
    void mysqlSkipsExistingPartitions() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mysql");
        LocalDate today = LocalDate.now();
        String existing = "p" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        when(mapper.selectAllPartitionNames()).thenReturn(List.of(existing));

        task.createNextPartition();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper).createPartitions(captor.capture());
        String defs = captor.getValue();
        assertEquals(29, defs.split("PARTITION ").length - 1);
        assertTrue(!defs.contains("PARTITION " + existing + " "), "已存在的分区名不应再次出现");
    }

    @Test
    @DisplayName("MySQL：分区全部已存在时不发出 DDL")
    void mysqlNoDdlWhenNothingMissing() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mysql");
        String[] names = new String[30];
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 30; i++) {
            names[i] = "p" + today.plusDays(i).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        when(mapper.selectAllPartitionNames()).thenReturn(List.of(names));

        task.createNextPartition();

        verify(mapper, never()).createPartitions(any());
    }

    @Test
    @DisplayName("MySQL：DDL 失败被吞掉，不抛给调度器")
    void mysqlDdlFailureIsSwallowed() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mysql");
        when(mapper.selectAllPartitionNames()).thenReturn(Collections.emptyList());
        when(mapper.createPartitions(any())).thenThrow(new RuntimeException("lock wait timeout"));

        assertDoesNotThrow(task::createNextPartition);
    }

    @Test
    @DisplayName("MariaDB：过期分区清理按 90 天保留期计算截止日")
    void mariadbCleanupUsesRetentionWindow() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mariadb");
        when(mapper.selectExpiredPartitions(any())).thenReturn(Collections.emptyList());

        task.cleanupExpiredPartitions();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectExpiredPartitions(captor.capture());
        String expected = LocalDate.now().minusDays(90).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertEquals(expected, captor.getValue());
        verify(mapper, never()).dropPartitions(any());
    }

    @Test
    @DisplayName("MariaDB：存在过期分区时按逗号拼接一次性删除")
    void mariadbDropsExpiredPartitionsInOneStatement() throws Exception {
        when(databaseIdProvider.getDatabaseId(any())).thenReturn("mariadb");
        when(mapper.selectExpiredPartitions(any())).thenReturn(List.of("p20250101", "p20250102"));

        task.cleanupExpiredPartitions();

        verify(mapper).dropPartitions("p20250101, p20250102");
    }
}
