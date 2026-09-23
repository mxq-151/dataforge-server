package com.bigdata.modules.metadata.service;

import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 元数据同步失败的可观测告警（风险项 R1）。
 * <p>
 * 背景：DDL 执行成功后服务端会触发元数据同步，但旧实现里同步失败只写一条 warn 日志，
 * 既不阻断执行、也没有任何可检索/可告警的落点，于是「表结构已经变了、平台上的描述还是旧的」
 * 这种状态会静默存在。
 * </p>
 * <p>
 * 本服务把失败升级为两条可观测信号：
 * <ol>
 *   <li><b>审计日志</b>：写入 {@code operation_audit_log}，{@code module=metadata}、
 *       {@code operation=METADATA_SYNC_FAILED}，可通过既有审计日志接口按模块/操作检索，
 *       也可直接被外部监控按行数或关键字告警。</li>
 *   <li><b>告警级日志</b>：以固定前缀 {@code [METADATA-SYNC-ALERT]} 打 ERROR 日志，
 *       便于日志采集侧直接配置告警规则。</li>
 * </ol>
 * </p>
 * <p>
 * 告警本身必须「不添乱」：所有异常都在内部吞掉，绝不能影响 DDL 执行结果。
 * </p>
 */
@Service
public class MetadataSyncAlertService {

    private static final Logger log = LoggerFactory.getLogger(MetadataSyncAlertService.class);

    /** 审计日志模块名 */
    public static final String AUDIT_MODULE = "metadata";
    /** 审计日志操作名：元数据同步失败 */
    public static final String AUDIT_OPERATION_SINGLE_FAILED = "METADATA_SYNC_FAILED";
    /** 审计日志操作名：元数据全量同步失败 */
    public static final String AUDIT_OPERATION_FULL_FAILED = "METADATA_FULL_SYNC_FAILED";
    /** 日志采集侧可用的告警前缀 */
    public static final String ALERT_LOG_PREFIX = "[METADATA-SYNC-ALERT]";

    @Autowired
    private AuditLogService auditLogService;

    /**
     * 单表元数据同步失败告警（DDL 执行后触发的那条链路）。
     */
    public void alertSingleTableSyncFailed(Long datasourceId, DatasourceType storageEngine,
                                           String databaseName, String tableName, String ddlType,
                                           String reason) {
        String table = qualified(databaseName, tableName);
        log.error("{} 单表元数据同步失败: datasourceId={}, engine={}, table={}, ddlType={}, reason={}",
                ALERT_LOG_PREFIX, datasourceId, storageEngine, table, ddlType, reason);
        record(AUDIT_OPERATION_SINGLE_FAILED, "TABLE", datasourceId, table, ddlType, reason);
    }

    /**
     * 数据源级全量元数据同步失败告警。
     */
    public void alertFullSyncFailed(Long datasourceId, DatasourceType storageEngine,
                                    int failCount, String reason) {
        log.error("{} 全量元数据同步失败: datasourceId={}, engine={}, failCount={}, reason={}",
                ALERT_LOG_PREFIX, datasourceId, storageEngine, failCount, reason);
        record(AUDIT_OPERATION_FULL_FAILED, "DATASOURCE", datasourceId,
                datasourceId != null ? String.valueOf(datasourceId) : null,
                "FULL_SYNC", reason);
    }

    private void record(String operation, String targetType, Long datasourceId, String targetName,
                        String operationType, String reason) {
        try {
            OperationAuditLog auditLog = new OperationAuditLog();
            auditLog.setModule(AUDIT_MODULE);
            auditLog.setOperation(operation);
            auditLog.setOperationType(operationType);
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(datasourceId);
            auditLog.setTargetName(targetName);
            auditLog.setUserId(SecurityUtils.getCurrentUserId());
            auditLog.setUsername(SecurityUtils.getCurrentUsername());
            auditLog.setTenantId(SecurityUtils.getCurrentTenantId());
            auditLog.setErrorMessage(reason);
            auditLog.setResponseStatus(500);
            auditLog.setIsSensitive(0);
            auditLog.setIsAiRequest(SecurityUtils.isAiRequest() ? 1 : 0);
            auditLog.setOperationTime(LocalDateTime.now());
            auditLog.setRequestParams("{\"datasourceId\":" + datasourceId
                    + ",\"target\":\"" + targetName + "\"}");
            auditLogService.saveAuditLogAsync(auditLog);
        } catch (Exception e) {
            log.error("{} 写入元数据同步失败审计日志时出错（不影响主流程）", ALERT_LOG_PREFIX, e);
        }
    }

    private String qualified(String databaseName, String tableName) {
        return (databaseName != null && !databaseName.isEmpty() ? databaseName + "." : "")
                + (tableName != null ? tableName : "");
    }
}
