package com.bigdata.common.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;

@Component
public class AuditLogPartitionTask {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogPartitionTask.class);

    @Autowired
    private DataSource dataSource;

    @Scheduled(cron = "0 0 2 * * ?")
    public void createNextPartition() {
        try (Connection conn = dataSource.getConnection()) {
            try (CallableStatement cs = conn.prepareCall("{CALL sp_create_audit_log_partition()}")) {
                cs.execute();
                logger.info("审计日志分区自动检查完成");
            }
        } catch (Exception e) {
            logger.error("审计日志分区自动创建失败", e);
        }
    }

    @Scheduled(cron = "0 0 3 1 * ?")
    public void cleanupExpiredPartitions() {
        try (Connection conn = dataSource.getConnection()) {
            try (CallableStatement cs = conn.prepareCall("{CALL sp_cleanup_audit_log_partitions()}")) {
                cs.execute();
                logger.info("审计日志过期分区清理完成");
            }
        } catch (Exception e) {
            if (!e.getMessage().contains("does not exist")) {
                logger.error("审计日志过期分区清理失败", e);
            }
        }
    }
}
