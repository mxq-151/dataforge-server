package com.bigdata.common.task;

import com.bigdata.modules.system.mapper.OperationAuditLogMapper;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 审计日志分区维护定时任务。
 *
 * <p>分区的查询与 DDL 语句统一定义在各方言 mapper XML 中：
 * MySQL/MariaDB 见 mapper/mysql|mariadb/OperationAuditLogMapper.xml（REORGANIZE/DROP PARTITION），
 * PostgreSQL 见 mapper/postgresql/OperationAuditLogMapper.xml（CREATE TABLE ... PARTITION OF / DROP TABLE）。
 * H2 2.x 不支持表分区，任务直接跳过。</p>
 *
 * <p>operation_audit_log 子分区统一命名为 pYYYYMMDD（按日 RANGE），跨库保持一致。</p>
 */
@Component
public class AuditLogPartitionTask {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogPartitionTask.class);

    /** 预创建未来分区的天数 */
    private static final int CREATE_DAYS_AHEAD = 30;
    /** 分区保留天数，超过该天数的日分区将被清理 */
    private static final int RETENTION_DAYS = 90;

    private static final DateTimeFormatter PARTITION_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter CUTOFF_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private OperationAuditLogMapper auditLogMapper;

    @Autowired
    private DatabaseIdProvider databaseIdProvider;

    @Autowired
    private DataSource dataSource;

    /** 惰性缓存当前数据库方言，来自 application.yml 中 databaseIdProvider 的映射（mysql/mariadb/postgresql/h2/...） */
    private volatile String dialect;

    private String dialect() {
        String d = dialect;
        if (d == null) {
            try {
                d = databaseIdProvider.getDatabaseId(dataSource);
            } catch (Exception e) {
                d = "other";
            }
            dialect = d == null ? "other" : d;
        }
        return dialect;
    }

    private boolean isPartitionFamily() {
        String d = dialect();
        return "mysql".equals(d) || "mariadb".equals(d);
    }

    private boolean isPostgres() {
        return "postgresql".equals(dialect());
    }

    /**
     * 每日 02:00 执行：确保未来 CREATE_DAYS_AHEAD 天每天都有 pYYYYMMDD 分区。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void createNextPartition() {
        if (isPartitionFamily()) {
            createNextPartitionMySQL();
        } else if (isPostgres()) {
            createNextPartitionPostgres();
        } else {
            logger.info("数据库 {} 不支持表分区，跳过审计日志分区预创建", dialect());
        }
    }

    private void createNextPartitionMySQL() {
        try {
            Set<String> existing = new HashSet<>(auditLogMapper.selectAllPartitionNames());
            List<String> missingNames = new ArrayList<>();
            List<String> missingDefs = new ArrayList<>();
            for (int i = 0; i < CREATE_DAYS_AHEAD; i++) {
                LocalDate partDate = LocalDate.now().plusDays(i);
                String partitionName = "p" + partDate.format(PARTITION_DATE_FMT);
                if (!existing.contains(partitionName)) {
                    missingNames.add(partitionName);
                    missingDefs.add("PARTITION " + partitionName +
                            " VALUES LESS THAN (TO_DAYS('" + partDate.plusDays(1) + "'))");
                }
            }
            if (!missingDefs.isEmpty()) {
                auditLogMapper.createPartitions(String.join(", ", missingDefs));
                logger.info("已创建审计日志分区: {}", missingNames);
            }
            logger.info("审计日志分区自动检查完成，已保障未来 {} 天分区", CREATE_DAYS_AHEAD);
        } catch (Exception e) {
            logger.error("审计日志分区自动创建失败", e);
        }
    }

    /**
     * PostgreSQL：逐日 CREATE TABLE ... PARTITION OF，边界为 [当日, 次日)。
     */
    private void createNextPartitionPostgres() {
        try {
            Set<String> existing = new HashSet<>(auditLogMapper.selectAllPartitionNames());
            List<String> created = new ArrayList<>();
            for (int i = 0; i < CREATE_DAYS_AHEAD; i++) {
                LocalDate partDate = LocalDate.now().plusDays(i);
                String partitionName = "p" + partDate.format(PARTITION_DATE_FMT);
                if (!existing.contains(partitionName)) {
                    auditLogMapper.createPartition(partitionName,
                            partDate.format(CUTOFF_DATE_FMT),
                            partDate.plusDays(1).format(CUTOFF_DATE_FMT));
                    created.add(partitionName);
                }
            }
            if (!created.isEmpty()) {
                logger.info("已创建审计日志分区: {}", created);
            }
            logger.info("审计日志分区自动检查完成，已保障未来 {} 天分区", CREATE_DAYS_AHEAD);
        } catch (Exception e) {
            logger.error("审计日志分区自动创建失败", e);
        }
    }

    /**
     * 每月 1 号 03:00 执行：清理 RETENTION_DAYS 天之前的过期日分区（永不删除 p_max）。
     * 仅 MySQL/MariaDB/PostgreSQL 使用分区清理；H2 等不支持分区的库跳过。
     */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void cleanupExpiredPartitions() {
        if (!isPartitionFamily() && !isPostgres()) {
            logger.info("数据库 {} 不支持表分区，跳过审计日志分区清理", dialect());
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
        String cutoffDate = cutoff.format(CUTOFF_DATE_FMT);
        try {
            List<String> expired = auditLogMapper.selectExpiredPartitions(cutoffDate);
            if (!expired.isEmpty()) {
                auditLogMapper.dropPartitions(String.join(", ", expired));
                logger.info("已清理过期审计日志分区: {}", expired);
            }
            logger.info("审计日志过期分区清理完成，共清理 {} 个分区", expired.size());
        } catch (Exception e) {
            logger.error("审计日志过期分区清理失败", e);
        }
    }
}