package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.AssignPermissionDTO;
import com.bigdata.modules.system.dto.FilePermissionDTO;
import com.bigdata.modules.system.dto.TablePermissionDTO;
import com.bigdata.modules.system.entity.SysFilePermission;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.service.PermissionService;
import com.bigdata.modules.system.vo.FilePermissionVO;
import com.bigdata.modules.system.vo.RolePermissionsVO;
import com.bigdata.modules.system.vo.TablePermissionVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/system/permission")
public class PermissionController {

    @Autowired
    private PermissionService permissionService;


    @GetMapping("/file/list")
    public Result<Page<FilePermissionVO>> getFileList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<FilePermissionVO> result = permissionService.getFilePermissionList(current, size, null);
        return Result.success(result);
    }

    @PostMapping("/file")
    public Result<FilePermissionVO> saveFile(@Valid @RequestBody FilePermissionDTO dto) {
        SysFilePermission permission = new SysFilePermission();
        BeanUtils.copyProperties(dto, permission);
        FilePermissionVO vo = permissionService.saveFilePermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @PutMapping("/file")
    public Result<FilePermissionVO> updateFile(@Valid @RequestBody FilePermissionDTO dto) {
        SysFilePermission permission = new SysFilePermission();
        BeanUtils.copyProperties(dto, permission);
        FilePermissionVO vo = permissionService.updateFilePermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @DeleteMapping("/file/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        permissionService.deleteFilePermission(id);
        return Result.success();
    }

    @GetMapping("/table/list")
    public Result<Page<TablePermissionVO>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<TablePermissionVO> result = permissionService.getTablePermissionList(current, size, null);
        return Result.success(result);
    }

    @PostMapping("/table")
    public Result<TablePermissionVO> saveTable(@Valid @RequestBody TablePermissionDTO dto) {
        SysTablePermission permission = new SysTablePermission();
        permission.setPermissionName(dto.getPermissionName());
        permission.setPermissionScope(dto.getPermissionScope());
        permission.setCanSelect(dto.getCanSelect() != null ? dto.getCanSelect() : 1);
        permission.setCanInsert(dto.getCanInsert() != null ? dto.getCanInsert() : 0);
        permission.setCanUpdate(dto.getCanUpdate() != null ? dto.getCanUpdate() : 0);
        permission.setCanDelete(dto.getCanDelete() != null ? dto.getCanDelete() : 0);
        permission.setCanExport(dto.getCanExport() != null ? dto.getCanExport() : 0);
        permission.setRowFilterSql(dto.getRowFilterSql());
        permission.setDataMaskingRule(dto.getDataMaskingRule());
        permission.setDescription(dto.getDescription());

        TablePermissionVO vo = permissionService.saveTablePermission(permission, dto.getRoleIds(), dto.getTableMetadataIds());
        return Result.success(vo);
    }

    @PutMapping("/table")
    public Result<TablePermissionVO> updateTable(@Valid @RequestBody TablePermissionDTO dto) {
        SysTablePermission permission = new SysTablePermission();
        permission.setId(dto.getId());
        permission.setPermissionName(dto.getPermissionName());
        permission.setPermissionScope(dto.getPermissionScope());
        permission.setCanSelect(dto.getCanSelect() != null ? dto.getCanSelect() : 1);
        permission.setCanInsert(dto.getCanInsert() != null ? dto.getCanInsert() : 0);
        permission.setCanUpdate(dto.getCanUpdate() != null ? dto.getCanUpdate() : 0);
        permission.setCanDelete(dto.getCanDelete() != null ? dto.getCanDelete() : 0);
        permission.setCanExport(dto.getCanExport() != null ? dto.getCanExport() : 0);
        permission.setRowFilterSql(dto.getRowFilterSql());
        permission.setDataMaskingRule(dto.getDataMaskingRule());
        permission.setDescription(dto.getDescription());

        TablePermissionVO vo = permissionService.updateTablePermission(permission, dto.getRoleIds(), dto.getTableMetadataIds());
        return Result.success(vo);
    }

    @DeleteMapping("/table/{id}")
    public Result<Void> deleteTable(@PathVariable Long id) {
        permissionService.deleteTablePermission(id);
        return Result.success();
    }

    @GetMapping("/role/{roleId}")
    public Result<RolePermissionsVO> getRolePermissions(@PathVariable Long roleId) {
        RolePermissionsVO vo = new RolePermissionsVO();
        vo.setMenuPermissionIds(permissionService.getMenuPermissionIdsByRoleId(roleId));
        vo.setFilePermissionIds(permissionService.getFilePermissionIdsByRoleId(roleId));
        vo.setTablePermissionIds(permissionService.getTablePermissionIdsByRoleId(roleId));
        return Result.success(vo);
    }

    @PostMapping("/role/{roleId}/assign")
    public Result<Void> assignRolePermissions(
            @PathVariable Long roleId,
            @Valid @RequestBody AssignPermissionDTO dto) {
        permissionService.assignRolePermissions(
                roleId,
                dto.getMenuPermissionIds(),
                dto.getFilePermissionIds(),
                dto.getTablePermissionIds()
        );
        return Result.success();
    }
}
