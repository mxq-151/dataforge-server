package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.FilePermissionDTO;
import com.bigdata.modules.system.dto.MenuPermissionDTO;
import com.bigdata.modules.system.dto.TablePermissionDTO;
import com.bigdata.modules.system.entity.SysButtonPermission;
import com.bigdata.modules.system.entity.SysFilePermission;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.service.PermissionService;
import com.bigdata.modules.system.vo.FilePermissionVO;
import com.bigdata.modules.system.vo.MenuPermissionVO;
import com.bigdata.modules.system.vo.TablePermissionVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/permission")
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    @GetMapping("/menu/list")
    public Result<Page<MenuPermissionVO>> getMenuList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String menuName) {
        Page<MenuPermissionVO> result = permissionService.getMenuPermissionList(current, size, menuName);
        return Result.success(result);
    }

    @GetMapping("/menu/tree")
    public Result<List<SysMenuPermission>> getMenuTree() {
        List<SysMenuPermission> tree = permissionService.getMenuTree();
        return Result.success(tree);
    }

    @GetMapping("/menu/all")
    public Result<List<SysMenuPermission>> getAllMenus() {
        List<SysMenuPermission> menus = permissionService.getAllMenuPermissions();
        return Result.success(menus);
    }

    @PostMapping("/menu")
    public Result<MenuPermissionVO> saveMenu(@Valid @RequestBody MenuPermissionDTO dto) {
        SysMenuPermission permission = new SysMenuPermission();
        BeanUtils.copyProperties(dto, permission);
        MenuPermissionVO vo = permissionService.saveMenuPermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @PutMapping("/menu")
    public Result<MenuPermissionVO> updateMenu(@Valid @RequestBody MenuPermissionDTO dto) {
        SysMenuPermission permission = new SysMenuPermission();
        BeanUtils.copyProperties(dto, permission);
        MenuPermissionVO vo = permissionService.updateMenuPermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @DeleteMapping("/menu/{id}")
    public Result<Void> deleteMenu(@PathVariable Long id) {
        permissionService.deleteMenuPermission(id);
        return Result.success();
    }

    @GetMapping("/button/list")
    public Result<Page<SysButtonPermission>> getButtonList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long menuPermissionId) {
        Page<SysButtonPermission> result = permissionService.getButtonPermissionList(current, size, menuPermissionId);
        return Result.success(result);
    }

    @GetMapping("/button/menu/{menuId}")
    public Result<List<SysButtonPermission>> getButtonsByMenuId(@PathVariable Long menuId) {
        List<SysButtonPermission> buttons = permissionService.getButtonPermissionsByMenuId(menuId);
        return Result.success(buttons);
    }

    @PostMapping("/button")
    public Result<Void> saveButton(@RequestBody SysButtonPermission permission) {
        permissionService.saveButtonPermission(permission);
        return Result.success();
    }

    @PutMapping("/button")
    public Result<Void> updateButton(@RequestBody SysButtonPermission permission) {
        permissionService.updateButtonPermission(permission);
        return Result.success();
    }

    @DeleteMapping("/button/{id}")
    public Result<Void> deleteButton(@PathVariable Long id) {
        permissionService.deleteButtonPermission(id);
        return Result.success();
    }

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
    public Result<Map<String, Object>> getRolePermissions(@PathVariable Long roleId) {
        Map<String, Object> data = Map.of(
                "menuPermissionIds", permissionService.getMenuPermissionIdsByRoleId(roleId),
                "buttonPermissionIds", permissionService.getButtonPermissionIdsByRoleId(roleId),
                "filePermissionIds", permissionService.getFilePermissionIdsByRoleId(roleId),
                "tablePermissionIds", permissionService.getTablePermissionIdsByRoleId(roleId)
        );
        return Result.success(data);
    }

    @PostMapping("/role/{roleId}/assign")
    public Result<Void> assignRolePermissions(
            @PathVariable Long roleId,
            @RequestBody Map<String, List<Long>> permissions) {
        permissionService.assignRolePermissions(
                roleId,
                permissions.get("menuPermissionIds"),
                permissions.get("buttonPermissionIds"),
                permissions.get("filePermissionIds"),
                permissions.get("tablePermissionIds")
        );
        return Result.success();
    }
}
