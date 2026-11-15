package com.bigdata.modules.system.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.AesUtils;
import com.bigdata.modules.system.dto.TenantDTO;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysTenantUser;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysTenantMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户隔离规则：一租户只能绑一个用户、用户只能属于一个租户、编码唯一。
 *
 * <p>这些约束是租户数据隔离的地基，冲突时必须在写库前拒绝，否则会留下跨租户脏绑定。
 * 此前该类行覆盖 3.3%，所有拒绝分支都没有用例。</p>
 */
@DisplayName("System模块 - 租户绑定与隔离规则")
class SysTenantServiceTest {

    private SysTenantService service;
    private SysTenantMapper sysTenantMapper;
    private SysTenantUserMapper sysTenantUserMapper;
    private SysTenantRoleMapper sysTenantRoleMapper;
    private SysUserMapper sysUserMapper;
    private AesUtils aesUtils;

    @BeforeEach
    void setUp() {
        service = new SysTenantService();
        sysTenantMapper = mock(SysTenantMapper.class);
        sysTenantUserMapper = mock(SysTenantUserMapper.class);
        sysTenantRoleMapper = mock(SysTenantRoleMapper.class);
        sysUserMapper = mock(SysUserMapper.class);
        aesUtils = mock(AesUtils.class);
        ReflectionTestUtils.setField(service, "sysTenantMapper", sysTenantMapper);
        ReflectionTestUtils.setField(service, "sysTenantUserMapper", sysTenantUserMapper);
        ReflectionTestUtils.setField(service, "sysTenantRoleMapper", sysTenantRoleMapper);
        ReflectionTestUtils.setField(service, "sysUserMapper", sysUserMapper);
        ReflectionTestUtils.setField(service, "aesUtils", aesUtils);
    }

    @Test
    @DisplayName("编码重复：拒绝创建，不落库")
    void rejectsDuplicateTenantCode() {
        when(sysTenantMapper.selectOne(any())).thenReturn(existingTenant(1L, "T001"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveTenant(dto("T001")));

        assertTrue(ex.getMessage().contains("租户编码已存在"));
        verify(sysTenantMapper, never()).insert(any());
    }

    @Test
    @DisplayName("创建成功：密码加密存储，状态缺省为启用")
    void savesTenantWithEncryptedPassword() {
        when(sysTenantMapper.selectOne(any())).thenReturn(null);
        when(aesUtils.encryptStored("plain-pwd")).thenReturn("ENC(plain-pwd)");
        doAnswer(invocation -> {
            ((SysTenant) invocation.getArgument(0)).setId(77L);
            return 1;
        }).when(sysTenantMapper).insert(any());

        TenantDTO dto = dto("T002");
        dto.setPassword("plain-pwd");
        service.saveTenant(dto);

        verify(aesUtils).encryptStored("plain-pwd");
        verify(sysTenantMapper).insert(org.mockito.ArgumentMatchers.argThat(tenant ->
                "ENC(plain-pwd)".equals(tenant.getPassword()) && Integer.valueOf(1).equals(tenant.getStatus())));
    }

    @Test
    @DisplayName("创建时带 userId：同一事务内完成绑定")
    void bindsUserOnCreate() {
        when(sysTenantMapper.selectOne(any())).thenReturn(null);
        when(aesUtils.encryptStored(any())).thenReturn("ENC");
        doAnswer(invocation -> {
            ((SysTenant) invocation.getArgument(0)).setId(77L);
            return 1;
        }).when(sysTenantMapper).insert(any());
        when(sysTenantMapper.selectById(77L)).thenReturn(existingTenant(77L, "T003"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        when(sysTenantUserMapper.selectByUserId(9L)).thenReturn(null);
        when(sysTenantUserMapper.selectUserIdsByTenantId(77L)).thenReturn(List.of());

        TenantDTO dto = dto("T003");
        dto.setUserId(9L);
        service.saveTenant(dto);

        verify(sysTenantUserMapper).insert(org.mockito.ArgumentMatchers.argThat(binding ->
                Long.valueOf(77L).equals(binding.getTenantId()) && Long.valueOf(9L).equals(binding.getUserId())));
    }

    @Test
    @DisplayName("更新不存在的租户：拒绝")
    void rejectsUpdateOfMissingTenant() {
        TenantDTO dto = dto("T004");
        dto.setId(404L);
        when(sysTenantMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateTenant(dto));
        assertTrue(ex.getMessage().contains("租户不存在"));
    }

    @Test
    @DisplayName("改编码撞上其他租户：拒绝")
    void rejectsUpdateToExistingCode() {
        TenantDTO dto = dto("T_NEW");
        dto.setId(1L);
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T_OLD"));
        when(sysTenantMapper.selectOne(any())).thenReturn(existingTenant(2L, "T_NEW"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateTenant(dto));
        assertTrue(ex.getMessage().contains("租户编码已存在"));
        verify(sysTenantMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("绑定用户：租户不存在时拒绝")
    void bindRejectsMissingTenant() {
        when(sysTenantMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("租户不存在"));
    }

    @Test
    @DisplayName("绑定用户：用户不存在时拒绝")
    void bindRejectsMissingUser() {
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T001"));
        when(sysUserMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("用户不存在"));
    }

    @Test
    @DisplayName("用户已绑到本租户：拒绝重复绑定")
    void bindRejectsDuplicateBinding() {
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T001"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        SysTenantUser binding = new SysTenantUser();
        binding.setTenantId(1L);
        when(sysTenantUserMapper.selectByUserId(9L)).thenReturn(binding);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("已绑定到此租户"));
        verify(sysTenantUserMapper, never()).insert(any());
    }

    @Test
    @DisplayName("用户已属于其他租户：拒绝，提示先解绑")
    void bindRejectsCrossTenantBinding() {
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T001"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        SysTenantUser binding = new SysTenantUser();
        binding.setTenantId(2L);
        when(sysTenantUserMapper.selectByUserId(9L)).thenReturn(binding);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("已绑定到其他租户"));
    }

    @Test
    @DisplayName("租户已有用户：一个租户只能绑定一个用户")
    void bindRejectsSecondUserInTenant() {
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T001"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        when(sysTenantUserMapper.selectByUserId(9L)).thenReturn(null);
        when(sysTenantUserMapper.selectUserIdsByTenantId(1L)).thenReturn(List.of(8L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("一个租户只能绑定一个用户"));
    }

    @Test
    @DisplayName("解绑未绑定的用户：拒绝")
    void unbindRejectsUnknownBinding() {
        when(sysTenantUserMapper.selectByUserId(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.unbindUser(1L, 9L));
        assertTrue(ex.getMessage().contains("未绑定到此租户"));
        verify(sysTenantUserMapper, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("删除租户：角色、用户绑定、租户本身全部清理")
    void deleteTenantCascades() {
        when(sysTenantMapper.selectById(1L)).thenReturn(existingTenant(1L, "T001"));

        service.deleteTenant(1L);

        verify(sysTenantRoleMapper).deleteByTenantId(1L);
        verify(sysTenantUserMapper).deleteByTenantId(1L);
        verify(sysTenantMapper).deleteById(1L);
    }

    @Test
    @DisplayName("用户已有租户：ensureTenantForUser 直接复用，不再新建")
    void ensureTenantReusesExisting() {
        when(sysTenantUserMapper.selectTenantIdByUserId(9L)).thenReturn(5L);
        SysTenant tenant = existingTenant(5L, "T005");
        when(sysTenantMapper.selectById(5L)).thenReturn(tenant);

        assertSame(tenant, service.ensureTenantForUser(9L));
        verify(sysTenantMapper, never()).insert(any());
    }

    @Test
    @DisplayName("用户不存在：ensureTenantForUser 拒绝")
    void ensureTenantRejectsMissingUser() {
        when(sysTenantUserMapper.selectTenantIdByUserId(9L)).thenReturn(null);
        when(sysUserMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.ensureTenantForUser(9L));
        assertEquals("用户不存在", ex.getMessage());
    }

    private TenantDTO dto(String code) {
        TenantDTO dto = new TenantDTO();
        dto.setTenantName("测试租户");
        dto.setTenantCode(code);
        return dto;
    }

    private SysTenant existingTenant(Long id, String code) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName("租户" + id);
        tenant.setStatus(1);
        return tenant;
    }
}
