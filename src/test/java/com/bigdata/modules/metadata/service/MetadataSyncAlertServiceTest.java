package com.bigdata.modules.metadata.service;

import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 元数据同步失败告警验证（风险项 R1）。
 * <p>
 * 旧实现里「DDL 执行后元数据同步失败」只写一条 warn 日志，没有任何可检索、可告警的落点。
 * 现在要求同时产出：审计日志（module=metadata / operation=METADATA_SYNC_FAILED）+ 告警级日志。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetadataSyncAlertServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MetadataSyncAlertService alertService;

    @BeforeEach
    void setUp() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ANALYST"));
        CustomUserDetails userDetails = new CustomUserDetails(
                42L, "tester", 7L, List.of("ROLE_ANALYST"), false, authorities, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void singleTableFailureIsRecordedInAuditLog() {
        alertService.alertSingleTableSyncFailed(9L, DatasourceType.POSTGRESQL,
                "testdb", "ad_events", "ALTER", "连接数据源失败");

        ArgumentCaptor<OperationAuditLog> captor = ArgumentCaptor.forClass(OperationAuditLog.class);
        verify(auditLogService, atLeastOnce()).saveAuditLogAsync(captor.capture());
        OperationAuditLog log = captor.getValue();

        assertEquals(MetadataSyncAlertService.AUDIT_MODULE, log.getModule());
        assertEquals(MetadataSyncAlertService.AUDIT_OPERATION_SINGLE_FAILED, log.getOperation());
        assertEquals("TABLE", log.getTargetType());
        assertEquals("testdb.ad_events", log.getTargetName());
        assertEquals(9L, log.getTargetId());
        assertEquals(500, log.getResponseStatus());
        assertEquals("连接数据源失败", log.getErrorMessage());
        assertEquals(42L, log.getUserId());
        assertEquals("tester", log.getUsername());
        assertEquals(7L, log.getTenantId());
        assertEquals(0, log.getIsSensitive());
    }

    @Test
    void fullSyncFailureIsRecordedInAuditLog() {
        alertService.alertFullSyncFailed(9L, DatasourceType.MYSQL, 3, "部分表同步失败");

        ArgumentCaptor<OperationAuditLog> captor = ArgumentCaptor.forClass(OperationAuditLog.class);
        verify(auditLogService, atLeastOnce()).saveAuditLogAsync(captor.capture());
        OperationAuditLog log = captor.getValue();

        assertEquals(MetadataSyncAlertService.AUDIT_OPERATION_FULL_FAILED, log.getOperation());
        assertEquals("DATASOURCE", log.getTargetType());
        assertTrue(log.getErrorMessage().contains("部分表同步失败"));
    }

    @Test
    void alertingFailureDoesNotPropagate() {
        // 告警写入自身异常时不能影响主流程（DDL 执行结果）
        org.mockito.Mockito.doThrow(new RuntimeException("audit db down"))
                .when(auditLogService).saveAuditLogAsync(org.mockito.ArgumentMatchers.any());

        alertService.alertSingleTableSyncFailed(9L, DatasourceType.POSTGRESQL,
                "testdb", "ad_events", "ALTER", "x");
    }
}
