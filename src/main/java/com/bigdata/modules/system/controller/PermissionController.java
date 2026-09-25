package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.AssignPermissionDTO;
import com.bigdata.modules.system.dto.DataResourcePermissionCheckDTO;
import com.bigdata.modules.system.dto.FilePermissionDTO;
import com.bigdata.modules.system.dto.TablePermissionDTO;
import com.bigdata.modules.system.entity.SysFilePermission;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.service.DataResourcePermissionCheckService;
import com.bigdata.modules.system.service.PermissionService;
import com.bigdata.modules.system.vo.DataResourcePermissionCheckVO;
import com.bigdata.modules.system.vo.FilePermissionVO;
import com.bigdata.modules.system.vo.RolePermissionsVO;
import com.bigdata.modules.system.vo.TablePermissionVO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;


@RestController
@RequestMapping("/system/permission")
public class PermissionController {

    private static final Logger logger = LoggerFactory.getLogger(PermissionController.class);

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DataResourcePermissionCheckService dataResourcePermissionCheckService;

    /** 共享密钥。留空表示未配置，接口一律拒绝（fail-close）。 */
    @Value("${permission.check.secret:}")
    private String checkSecret;

    /** 数据资源权限校验的共享密钥请求头。 */
    private static final String CHECK_TOKEN_HEADER = "X-Permission-Check-Token";

    /**
     * 数据资源权限批量校验。
     * <p>
     * 供 Spark 等外部执行作业回调：作业侧上报本次访问的资源清单，服务端按提交用户判定。
     * 请求方是执行引擎（非浏览器登录态），因此不要求 JWT；改用共享密钥请求头
     * {@value #CHECK_TOKEN_HEADER} 鉴权，用户身份仍由请求体给出。
     * </p>
     */
    @PostMapping("/check")
    public Result<DataResourcePermissionCheckVO> check(
            @RequestHeader(value = CHECK_TOKEN_HEADER, required = false) String checkToken,
            @RequestBody DataResourcePermissionCheckDTO dto) {
        verifyCheckToken(checkToken);
        return Result.success(dataResourcePermissionCheckService.check(dto));
    }

    /**
     * 校验共享密钥。
     * <p>
     * <b>默认拒绝</b>：未配置密钥、请求头缺失或不匹配，一律拒绝调用，不做「验不了就放行」。
     * 比较用常量时间算法，避免通过响应耗时逐字节猜测密钥。
     * </p>
     */
    private void verifyCheckToken(String checkToken) {
        if (!StringUtils.hasText(checkSecret)) {
            logger.error("数据资源权限校验接口未配置共享密钥（permission.check.secret），已拒绝调用");
            throw new BusinessException(503, "数据资源权限校验接口未配置共享密钥，已拒绝调用");
        }
        if (!StringUtils.hasText(checkToken)
                || !MessageDigest.isEqual(checkSecret.trim().getBytes(StandardCharsets.UTF_8),
                        checkToken.trim().getBytes(StandardCharsets.UTF_8))) {
            logger.warn("数据资源权限校验接口共享密钥校验未通过，已拒绝调用");
            throw new BusinessException(403, "共享密钥校验未通过");
        }
    }


    @GetMapping("/file/list")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Page<FilePermissionVO>> getFileList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<FilePermissionVO> result = permissionService.getFilePermissionList(current, size, null);
        return Result.success(result);
    }

    @AuditLog(module = "权限管理", operation = "创建文件权限", targetType = "FilePermission")
    @PostMapping("/file")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<FilePermissionVO> saveFile(@Valid @RequestBody FilePermissionDTO dto) {
        SysFilePermission permission = new SysFilePermission();
        BeanUtils.copyProperties(dto, permission);
        FilePermissionVO vo = permissionService.saveFilePermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @AuditLog(module = "权限管理", operation = "更新文件权限", targetType = "FilePermission")
    @PutMapping("/file")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<FilePermissionVO> updateFile(@Valid @RequestBody FilePermissionDTO dto) {
        SysFilePermission permission = new SysFilePermission();
        BeanUtils.copyProperties(dto, permission);
        FilePermissionVO vo = permissionService.updateFilePermission(permission, dto.getRoleIds());
        return Result.success(vo);
    }

    @AuditLog(module = "权限管理", operation = "删除文件权限", targetType = "FilePermission")
    @DeleteMapping("/file/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> deleteFile(@PathVariable Long id) {
        permissionService.deleteFilePermission(id);
        return Result.success();
    }

    @GetMapping("/table/list")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Page<TablePermissionVO>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Page<TablePermissionVO> result = permissionService.getTablePermissionList(current, size, null);
        return Result.success(result);
    }

    @AuditLog(module = "权限管理", operation = "创建表权限", targetType = "TablePermission")
    @PostMapping("/table")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<TablePermissionVO> saveTable(@Valid @RequestBody TablePermissionDTO dto) {
        SysTablePermission permission = new SysTablePermission();
        permission.setPermissionName(dto.getPermissionName());
        permission.setDatasourceName(dto.getDatasourceName());
        permission.setDatabaseName(dto.getDatabaseName());
        permission.setTableNames(dto.getTableNames() != null ? String.join(",", dto.getTableNames()) : null);
        permission.setPermissionScope(dto.getPermissionScope());
        permission.setCanSelect(dto.getCanSelect() != null ? dto.getCanSelect() : 1);
        permission.setCanInsert(dto.getCanInsert() != null ? dto.getCanInsert() : 0);
        permission.setCanUpdate(dto.getCanUpdate() != null ? dto.getCanUpdate() : 0);
        permission.setCanDelete(dto.getCanDelete() != null ? dto.getCanDelete() : 0);
        permission.setCanExport(dto.getCanExport() != null ? dto.getCanExport() : 0);
        permission.setDataMaskingRule(dto.getDataMaskingRule());
        permission.setDescription(dto.getDescription());

        TablePermissionVO vo = permissionService.saveTablePermission(permission, dto.getRoleIds(),
                dto.getColumnPermissions(), dto.getRowFilterSql());
        return Result.success(vo);
    }

    @AuditLog(module = "权限管理", operation = "更新表权限", targetType = "TablePermission")
    @PutMapping("/table")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<TablePermissionVO> updateTable(@Valid @RequestBody TablePermissionDTO dto) {
        SysTablePermission permission = new SysTablePermission();
        permission.setId(dto.getId());
        permission.setPermissionName(dto.getPermissionName());
        permission.setDatasourceName(dto.getDatasourceName());
        permission.setDatabaseName(dto.getDatabaseName());
        permission.setTableNames(dto.getTableNames() != null ? String.join(",", dto.getTableNames()) : null);
        permission.setPermissionScope(dto.getPermissionScope());
        permission.setCanSelect(dto.getCanSelect() != null ? dto.getCanSelect() : 1);
        permission.setCanInsert(dto.getCanInsert() != null ? dto.getCanInsert() : 0);
        permission.setCanUpdate(dto.getCanUpdate() != null ? dto.getCanUpdate() : 0);
        permission.setCanDelete(dto.getCanDelete() != null ? dto.getCanDelete() : 0);
        permission.setCanExport(dto.getCanExport() != null ? dto.getCanExport() : 0);
        permission.setDataMaskingRule(dto.getDataMaskingRule());
        permission.setDescription(dto.getDescription());

        TablePermissionVO vo = permissionService.updateTablePermission(permission, dto.getRoleIds(),
                dto.getColumnPermissions(), dto.getRowFilterSql());
        return Result.success(vo);
    }

    @AuditLog(module = "权限管理", operation = "删除表权限", targetType = "TablePermission")
    @DeleteMapping("/table/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> deleteTable(@PathVariable Long id) {
        permissionService.deleteTablePermission(id);
        return Result.success();
    }

    @GetMapping("/role/{roleId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<RolePermissionsVO> getRolePermissions(@PathVariable Long roleId) {
        RolePermissionsVO vo = new RolePermissionsVO();
        vo.setMenuPermissionIds(permissionService.getMenuPermissionIdsByRoleId(roleId));
        vo.setFilePermissionIds(permissionService.getFilePermissionIdsByRoleId(roleId));
        vo.setTablePermissionIds(permissionService.getTablePermissionIdsByRoleId(roleId));
        return Result.success(vo);
    }

    @AuditLog(module = "权限管理", operation = "分配角色权限", targetType = "Role")
    @PostMapping("/role/{roleId}/assign")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
