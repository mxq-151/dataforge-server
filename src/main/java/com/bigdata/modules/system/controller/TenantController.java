package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.TenantDTO;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.vo.TenantVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/tenant")
public class TenantController {

    @Autowired
    private SysTenantService sysTenantService;

    @GetMapping("/list")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Page<TenantVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String tenantName,
            @RequestParam(required = false) Integer status) {
        Page<SysTenant> page = sysTenantService.getTenantList(current, size, tenantName, status);
        Page<TenantVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<List<TenantVO>> getAllTenants() {
        List<SysTenant> tenants = sysTenantService.getAllTenants();
        List<TenantVO> voList = tenants.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/{id}")
    public Result<TenantVO> getById(@PathVariable Long id) {
        SysTenant tenant = sysTenantService.getTenantById(id);
        return Result.success(toVO(tenant));
    }

    @GetMapping("/user/{userId}")
    public Result<TenantVO> getByUserId(@PathVariable Long userId) {
        SysTenant tenant = sysTenantService.getTenantByUserId(userId);
        return Result.success(toVO(tenant));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module="租户管理", operation="创建租户", targetType="Tenant")
    public Result<Void> save(@Valid @RequestBody TenantDTO dto) {
        sysTenantService.saveTenant(dto);
        return Result.success();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module="租户管理", operation="更新租户", targetType="Tenant")
    public Result<Void> update(@Valid @RequestBody TenantDTO dto) {
        sysTenantService.updateTenant(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module="租户管理", operation="删除租户", targetType="Tenant")
    public Result<Void> delete(@PathVariable Long id) {
        sysTenantService.deleteTenant(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysTenantService.changeStatus(id, status);
        return Result.success();
    }

    @PostMapping("/{tenantId}/bind-user/{userId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module="租户管理", operation="绑定用户到租户", targetType="Tenant")
    public Result<Void> bindUser(@PathVariable Long tenantId, @PathVariable Long userId) {
        sysTenantService.bindUser(tenantId, userId);
        return Result.success();
    }

    @DeleteMapping("/{tenantId}/unbind-user/{userId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module="租户管理", operation="解绑用户与租户", targetType="Tenant")
    public Result<Void> unbindUser(@PathVariable Long tenantId, @PathVariable Long userId) {
        sysTenantService.unbindUser(tenantId, userId);
        return Result.success();
    }

    private TenantVO toVO(SysTenant entity) {
        if (entity == null) {
            return null;
        }
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(entity, vo);

        SysUser user = sysTenantService.getUserByTenantId(entity.getId());
        if (user != null) {
            vo.setUserId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setUserRealName(user.getRealName());
        }

        return vo;
    }
}
