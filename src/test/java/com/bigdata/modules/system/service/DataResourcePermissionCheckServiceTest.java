package com.bigdata.modules.system.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.system.dto.DataResourcePermissionCheckDTO;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysUserMapper;
import com.bigdata.modules.system.vo.DataResourcePermissionCheckVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据资源权限批量校验的 fail-close 语义。
 *
 * <p>这是「作业在引擎侧执行、服务端拿不到结果集」时唯一的权限判定入口，
 * 判不了必须拒绝：身份不明、缺少表名/路径、资源类型不认识都不能放行。</p>
 */
@DisplayName("System模块 - 数据资源权限批量校验")
class DataResourcePermissionCheckServiceTest {

    private DataResourcePermissionCheckService service;
    private DataAccessPermissionService permissionService;
    private SysUserMapper sysUserMapper;

    @BeforeEach
    void setUp() {
        service = new DataResourcePermissionCheckService();
        permissionService = mock(DataAccessPermissionService.class);
        sysUserMapper = mock(SysUserMapper.class);
        ReflectionTestUtils.setField(service, "dataAccessPermissionService", permissionService);
        ReflectionTestUtils.setField(service, "sysUserMapper", sysUserMapper);
    }

    @Test
    @DisplayName("既无 userId 也无 username：拒绝校验（400），不做默认放行")
    void rejectsWhenIdentityMissing() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.check(new DataResourcePermissionCheckDTO()));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("用户名查不到用户：同样按 400 拒绝")
    void rejectsWhenUsernameUnknown() {
        DataResourcePermissionCheckDTO request = new DataResourcePermissionCheckDTO();
        request.setUsername("ghost");
        when(sysUserMapper.selectByUsername("ghost")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.check(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("只给用户名时按用户名反查 userId")
    void resolvesUserIdByUsername() {
        SysUser user = new SysUser();
        user.setId(42L);
        when(sysUserMapper.selectByUsername("alice")).thenReturn(user);
        when(permissionService.hasTablePermissionForUser(eq(42L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        DataResourcePermissionCheckDTO request = new DataResourcePermissionCheckDTO();
        request.setUsername("alice");
        request.getResources().add(tableResource("mysql1", "db", "t_order", null));

        assertTrue(service.check(request).isAllowed());
    }

    @Test
    @DisplayName("没有资源清单：直接通过，不产生违规项")
    void emptyResourceListIsAllowed() {
        DataResourcePermissionCheckDTO request = new DataResourcePermissionCheckDTO();
        request.setUserId(1L);

        DataResourcePermissionCheckVO result = service.check(request);

        assertTrue(result.isAllowed());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    @DisplayName("表资源缺少表名：拒绝，理由是资源描述不完整")
    void rejectsTableWithoutName() {
        DataResourcePermissionCheckDTO request = requestWith(tableResource("mysql1", "db", "  ", null));

        DataResourcePermissionCheckVO result = service.check(request);

        assertFalse(result.isAllowed());
        assertEquals(1, result.getViolations().size());
        assertTrue(result.getViolations().get(0).getReason().contains("缺少表名"));
    }

    @Test
    @DisplayName("表无权限：拒绝并给出「无权对表 ... 执行 SELECT」")
    void rejectsTableWithoutPermission() {
        when(permissionService.hasTablePermissionForUser(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        DataResourcePermissionCheckDTO request = requestWith(tableResource("mysql1", "db", "t_order", null));

        DataResourcePermissionCheckVO result = service.check(request);

        assertFalse(result.isAllowed());
        DataResourcePermissionCheckVO.Violation violation = result.getViolations().get(0);
        assertEquals("mysql1:db.t_order", violation.getResource());
        assertEquals("SELECT", violation.getOperation());
        assertTrue(violation.getReason().contains("无权对表"));
    }

    @Test
    @DisplayName("表权限通过：不产生违规项")
    void allowsTableWithPermission() {
        when(permissionService.hasTablePermissionForUser(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        assertTrue(service.check(requestWith(tableResource("mysql1", "db", "t_order", "select"))).isAllowed());
    }

    @Test
    @DisplayName("文件资源缺路径：拒绝")
    void rejectsFileWithoutPath() {
        DataResourcePermissionCheckDTO request = requestWith(fileResource(null, 1L, "read"));

        DataResourcePermissionCheckVO result = service.check(request);

        assertFalse(result.isAllowed());
        assertTrue(result.getViolations().get(0).getReason().contains("缺少文件路径"));
    }

    @Test
    @DisplayName("文件 READ 走 FILE_PERMISSION_READ")
    void fileReadUsesReadPermission() {
        when(permissionService.hasFilePermissionForUser(eq(1L), eq("/a/b.txt"), eq(9L),
                eq(DataAccessPermissionService.FILE_PERMISSION_READ))).thenReturn(true);

        DataResourcePermissionCheckDTO request = requestWith(fileResource("/a/b.txt", 9L, null));

        assertTrue(service.check(request).isAllowed());
    }

    @Test
    @DisplayName("文件 WRITE 走 FILE_PERMISSION_WRITE")
    void fileWriteUsesWritePermission() {
        when(permissionService.hasFilePermissionForUser(eq(1L), eq("/a/b.txt"), eq(9L),
                eq(DataAccessPermissionService.FILE_PERMISSION_WRITE))).thenReturn(true);

        DataResourcePermissionCheckDTO request = requestWith(fileResource("/a/b.txt", 9L, "write"));

        assertTrue(service.check(request).isAllowed());
    }

    @Test
    @DisplayName("无法识别的资源类型：拒绝，不当作通过")
    void rejectsUnknownResourceType() {
        DataResourcePermissionCheckDTO.Resource resource = new DataResourcePermissionCheckDTO.Resource();
        resource.setResourceType("KAFKA_TOPIC");
        resource.setTableName("t1");

        DataResourcePermissionCheckVO result = service.check(requestWith(resource));

        assertFalse(result.isAllowed());
        assertTrue(result.getViolations().get(0).getReason().contains("无法识别的资源类型"));
    }

    @Test
    @DisplayName("多个资源：任一被拒即整体拒绝，null 资源被跳过")
    void aggregatesViolationsAcrossResources() {
        when(permissionService.hasTablePermissionForUser(anyLong(), anyString(), anyString(), eq("t_ok"), anyString()))
                .thenReturn(true);
        when(permissionService.hasTablePermissionForUser(anyLong(), anyString(), anyString(), eq("t_denied"), anyString()))
                .thenReturn(false);
        DataResourcePermissionCheckDTO request = requestWith(
                tableResource("ds", "db", "t_ok", null),
                tableResource("ds", "db", "t_denied", null),
                null);

        DataResourcePermissionCheckVO result = service.check(request);

        assertFalse(result.isAllowed());
        assertEquals(1, result.getViolations().size());
        assertEquals("ds:db.t_denied", result.getViolations().get(0).getResource());
    }

    private DataResourcePermissionCheckDTO requestWith(DataResourcePermissionCheckDTO.Resource... resources) {
        DataResourcePermissionCheckDTO request = new DataResourcePermissionCheckDTO();
        request.setUserId(1L);
        java.util.Collections.addAll(request.getResources(), resources);
        return request;
    }

    private DataResourcePermissionCheckDTO.Resource tableResource(String ds, String db, String table, String op) {
        DataResourcePermissionCheckDTO.Resource resource = new DataResourcePermissionCheckDTO.Resource();
        resource.setResourceType("TABLE");
        resource.setDatasourceName(ds);
        resource.setDatabaseName(db);
        resource.setTableName(table);
        resource.setOperation(op);
        return resource;
    }

    private DataResourcePermissionCheckDTO.Resource fileResource(String path, Long storageConfigId, String op) {
        DataResourcePermissionCheckDTO.Resource resource = new DataResourcePermissionCheckDTO.Resource();
        resource.setResourceType("FILE");
        resource.setPath(path);
        resource.setStorageConfigId(storageConfigId);
        resource.setOperation(op);
        return resource;
    }
}
