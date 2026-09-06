package com.bigdata.common.security;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataAccessPermissionService {

    public static final int FILE_PERMISSION_READ = 1;
    public static final int FILE_PERMISSION_WRITE = 2;

    public static final String TABLE_OP_SELECT = "SELECT";
    public static final String TABLE_OP_INSERT = "INSERT";
    public static final String TABLE_OP_UPDATE = "UPDATE";
    public static final String TABLE_OP_DELETE = "DELETE";
    public static final String TABLE_OP_EXPORT = "EXPORT";

    public static final int PROJECT_PERMISSION_READ = 1;
    public static final int PROJECT_PERMISSION_EDIT = 2;
    public static final int PROJECT_PERMISSION_DELETE = 3;

    private static final String ADMIN_ROLE_CODE = "ROLE_SUPER_ADMIN";

    @Autowired
    private SysTenantUserMapper sysTenantUserMapper;

    @Autowired
    private SysTenantRoleMapper sysTenantRoleMapper;

    @Autowired
    private SysRoleFilePermissionMapper sysRoleFilePermissionMapper;

    @Autowired
    private SysRoleTablePermissionMapper sysRoleTablePermissionMapper;

    @Autowired
    private SysRoleProjectPermissionMapper sysRoleProjectPermissionMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private SysFilePermissionMapper sysFilePermissionMapper;

    @Autowired
    private SysTablePermissionMapper sysTablePermissionMapper;

    @Autowired
    private SysTablePermissionColumnMapper tablePermissionColumnMapper;

    @Autowired
    private SysTablePermissionRowMapper tablePermissionRowMapper;

    public boolean isAdmin() {
        List<String> roles = SecurityUtils.getCurrentUserRoles();
        return roles != null && roles.stream().anyMatch(r -> ADMIN_ROLE_CODE.equalsIgnoreCase(r));
    }

    public void checkFileReadPermission(String path, Long storageConfigId) {
        checkFilePermission(path, storageConfigId, FILE_PERMISSION_READ);
    }

    public void checkFileWritePermission(String path, Long storageConfigId) {
        checkFilePermission(path, storageConfigId, FILE_PERMISSION_WRITE);
    }

    public void checkFilePermission(String path, Long storageConfigId, int requiredType) {
        if (isAdmin()) {
            return;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(403, "未登录，无权访问");
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            throw new BusinessException(403, "无文件访问权限");
        }

        List<Long> filePermissionIds = getFilePermissionIdsByRoleIds(roleIds);
        if (filePermissionIds.isEmpty()) {
            throw new BusinessException(403, "无文件访问权限");
        }

        List<SysFilePermission> permissions = sysFilePermissionMapper.selectBatchIds(filePermissionIds);
        for (SysFilePermission perm : permissions) {
            if (matchesFilePermission(perm, path, storageConfigId, requiredType)) {
                return;
            }
        }

        throw new BusinessException(403, "无权访问文件: " + path);
    }

    public boolean hasFileReadPermission(String path, Long storageConfigId) {
        return hasFilePermission(path, storageConfigId, FILE_PERMISSION_READ);
    }

    public boolean hasFileWritePermission(String path, Long storageConfigId) {
        return hasFilePermission(path, storageConfigId, FILE_PERMISSION_WRITE);
    }

    public boolean hasFilePermission(String path, Long storageConfigId, int requiredType) {
        if (isAdmin()) {
            return true;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return false;
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return false;
        }

        List<Long> filePermissionIds = getFilePermissionIdsByRoleIds(roleIds);
        if (filePermissionIds.isEmpty()) {
            return false;
        }

        List<SysFilePermission> permissions = sysFilePermissionMapper.selectBatchIds(filePermissionIds);
        for (SysFilePermission perm : permissions) {
            if (matchesFilePermission(perm, path, storageConfigId, requiredType)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesFilePermission(SysFilePermission perm, String path, Long storageConfigId, int requiredType) {
        if (perm.getStorageConfigId() != null && storageConfigId != null
                && !perm.getStorageConfigId().equals(storageConfigId)) {
            return false;
        }

        if (perm.getPermissionType() != null && perm.getPermissionType() < requiredType) {
            return false;
        }

        String permPath = perm.getFilePath();
        if (permPath == null) {
            return false;
        }

        if (permPath.equals("*")) {
            return true;
        }

        // 根目录"/"需要区分是否递归：非递归时仅匹配自身，递归时匹配所有路径
        if (permPath.equals("/")) {
            if (perm.getRecursive() != null && perm.getRecursive() == 1) {
                return true;
            }
            return "/".equals(path);
        }

        if (path.equals(permPath)) {
            return true;
        }

        if (perm.getRecursive() != null && perm.getRecursive() == 1) {
            String normalizedPermPath = permPath.endsWith("/") ? permPath : permPath + "/";
            String normalizedPath = path.endsWith("/") ? path : path + "/";
            return normalizedPath.startsWith(normalizedPermPath) || path.startsWith(normalizedPermPath);
        }

        return false;
    }

    public void checkTablePermission(String datasourceName, String databaseName, String tableName, String operation) {
        if (isAdmin()) {
            return;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(403, "未登录，无权访问");
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            throw new BusinessException(403, "无数据表访问权限");
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            throw new BusinessException(403, "无数据表访问权限");
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        for (SysTablePermission perm : permissions) {
            if (matchesTablePermission(perm, datasourceName, databaseName, tableName)
                    && hasTableOperation(perm, operation)) {
                return;
            }
        }

        throw new BusinessException(403, "无权对数据表 " + databaseName + "." + tableName + " 执行 " + operation + " 操作");
    }

    public boolean hasTablePermission(String datasourceName, String databaseName, String tableName, String operation) {
        if (isAdmin()) {
            return true;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return false;
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return false;
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return false;
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        for (SysTablePermission perm : permissions) {
            if (matchesTablePermission(perm, datasourceName, databaseName, tableName)
                    && hasTableOperation(perm, operation)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesTablePermission(SysTablePermission perm, String datasourceName, String databaseName, String tableName) {
        // 调用方未指定数据源时，跳过数据源维度的匹配
        if (datasourceName != null && perm.getDatasourceName() != null && !perm.getDatasourceName().isEmpty()
                && !perm.getDatasourceName().equals(datasourceName)) {
            return false;
        }

        if (perm.getDatabaseName() != null && !perm.getDatabaseName().isEmpty()
                && !perm.getDatabaseName().equals(databaseName)) {
            return false;
        }

        if (perm.getTableNames() == null || perm.getTableNames().isEmpty()) {
            return true;
        }

        List<String> permTableList = parseTableNames(perm.getTableNames());
        if (permTableList.contains("*") || permTableList.contains(tableName)) {
            return true;
        }

        return false;
    }

    private List<String> parseTableNames(String tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return Collections.emptyList();
        }
        String trimmed = tableNames.trim();
        if (trimmed.startsWith("[")) {
            try {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            } catch (Exception ignored) {
            }
        }
        return Arrays.stream(trimmed.split(","))
                .map(s -> s.trim().replace("\"", "").replace("'", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public Set<String> getAccessibleDatabaseNames(String datasourceName) {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        Set<String> dbNames = new HashSet<>();
        for (SysTablePermission perm : permissions) {
            // 调用方未指定数据源时，跳过数据源维度的匹配
            if (datasourceName != null && perm.getDatasourceName() != null && !perm.getDatasourceName().isEmpty()
                    && !perm.getDatasourceName().equals(datasourceName)) {
                continue;
            }
            if (perm.getDatabaseName() != null && !perm.getDatabaseName().isEmpty()) {
                dbNames.add(perm.getDatabaseName());
            }
        }
        return dbNames;
    }

    public Set<String> getAccessibleTableNames(String datasourceName, String databaseName) {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        Set<String> tableNames = new HashSet<>();
        for (SysTablePermission perm : permissions) {
            if (!matchesDatasourceAndDatabase(perm, datasourceName, databaseName)) {
                continue;
            }
            List<String> permTableList = parseTableNames(perm.getTableNames());
            if (permTableList.contains("*")) {
                return null;
            }
            tableNames.addAll(permTableList);
        }
        return tableNames;
    }

    private boolean matchesDatasourceAndDatabase(SysTablePermission perm, String datasourceName, String databaseName) {
        // 调用方未指定数据源时，跳过数据源维度的匹配
        if (datasourceName != null && perm.getDatasourceName() != null && !perm.getDatasourceName().isEmpty()
                && !perm.getDatasourceName().equals(datasourceName)) {
            return false;
        }
        if (perm.getDatabaseName() != null && !perm.getDatabaseName().isEmpty()
                && !perm.getDatabaseName().equals(databaseName)) {
            return false;
        }
        return true;
    }

    public SysTablePermission getMergedTablePermission(String datasourceName, String databaseName, String tableName) {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return null;
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return null;
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return null;
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        SysTablePermission merged = null;
        for (SysTablePermission perm : permissions) {
            if (!matchesTablePermission(perm, datasourceName, databaseName, tableName)) {
                continue;
            }
            if (merged == null) {
                merged = new SysTablePermission();
                merged.setCanSelect(perm.getCanSelect());
                merged.setCanInsert(perm.getCanInsert());
                merged.setCanUpdate(perm.getCanUpdate());
                merged.setCanDelete(perm.getCanDelete());
                merged.setCanExport(perm.getCanExport());
                merged.setDataMaskingRule(perm.getDataMaskingRule());
            } else {
                merged.setCanSelect(mergePermissionValue(merged.getCanSelect(), perm.getCanSelect()));
                merged.setCanInsert(mergePermissionValue(merged.getCanInsert(), perm.getCanInsert()));
                merged.setCanUpdate(mergePermissionValue(merged.getCanUpdate(), perm.getCanUpdate()));
                merged.setCanDelete(mergePermissionValue(merged.getCanDelete(), perm.getCanDelete()));
                merged.setCanExport(mergePermissionValue(merged.getCanExport(), perm.getCanExport()));
                if (perm.getDataMaskingRule() != null && !perm.getDataMaskingRule().isEmpty()) {
                    merged.setDataMaskingRule(perm.getDataMaskingRule());
                }
            }
        }

        return merged;
    }

    private int mergePermissionValue(Integer existing, Integer incoming) {
        if (existing == null) existing = 0;
        if (incoming == null) incoming = 0;
        return Math.max(existing, incoming);
    }

    /**
     * 获取当前用户对指定数据表的行级过滤条件（指定行）。
     * <p>
     * 返回 {@code null} 表示不做行级过滤（所有行可见）。
     * 合并规则：任一命中权限对该表未配置行过滤时，视为不做行过滤（取宽松）；
     * 否则将所有命中权限对该表的行过滤条件用 AND 组合后返回。
     * </p>
     */
    public String getMergedRowFilterSql(String datasourceName, String databaseName, String tableName) {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return null;
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return null;
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return null;
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        List<String> filters = new ArrayList<>();
        for (SysTablePermission perm : permissions) {
            if (!matchesTablePermission(perm, datasourceName, databaseName, tableName)) {
                continue;
            }
            List<SysTablePermissionRow> rowBindings =
                    tablePermissionRowMapper.selectByPermissionIdAndTable(perm.getId(), tableName);
            if (rowBindings == null || rowBindings.isEmpty()) {
                // 该条权限未对该表做行过滤，表示该表所有行可见，整体放宽为不过滤
                return null;
            }
            for (SysTablePermissionRow r : rowBindings) {
                if (r.getRowFilterSql() != null && !r.getRowFilterSql().isBlank()) {
                    filters.add(r.getRowFilterSql().trim());
                }
            }
        }
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return filters.stream()
                .map(f -> "(" + f + ")")
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    /**
     * 获取当前用户对指定数据表允许可见的列集合（指定列白名单）。
     * <p>
     * 返回 {@code null} 表示列不受限（所有列可见）；返回非空集合表示仅集合内的列可见。
     * 合并规则：任一命中的权限未对表做列限制（该权限下该表无列记录）时，视为所有列可见（取并集放宽），
     * 否则对所有命中权限的列白名单取并集。
     * </p>
     */
    public Set<String> getAllowedColumns(String datasourceName, String databaseName, String tableName) {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        Set<String> merged = new HashSet<>();
        for (SysTablePermission perm : permissions) {
            if (!matchesTablePermission(perm, datasourceName, databaseName, tableName)) {
                continue;
            }
            List<SysTablePermissionColumn> columnBindings =
                    tablePermissionColumnMapper.selectByPermissionIdAndTable(perm.getId(), tableName);
            if (columnBindings == null || columnBindings.isEmpty()) {
                // 该条权限未对该表限制列，表示该表所有列可见，整体放宽为不受限
                return null;
            }
            for (SysTablePermissionColumn c : columnBindings) {
                if (c.getColumnName() != null && !c.getColumnName().isBlank()) {
                    merged.add(c.getColumnName());
                }
            }
        }
        return merged;
    }

    public void checkProjectPermission(Long projectId, int requiredType) {
        if (isAdmin()) {
            return;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(403, "未登录，无权访问");
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            throw new BusinessException(403, "无项目访问权限");
        }

        List<SysRoleProjectPermission> permissions = sysRoleProjectPermissionMapper.selectByRoleIds(roleIds);
        for (SysRoleProjectPermission perm : permissions) {
            if (perm.getProjectId().equals(projectId) && perm.getPermissionType() >= requiredType) {
                return;
            }
        }

        String typeName = switch (requiredType) {
            case PROJECT_PERMISSION_READ -> "读取";
            case PROJECT_PERMISSION_EDIT -> "编辑";
            case PROJECT_PERMISSION_DELETE -> "删除";
            default -> "访问";
        };
        throw new BusinessException(403, "无权" + typeName + "该项目");
    }

    public boolean hasProjectPermission(Long projectId, int requiredType) {
        if (isAdmin()) {
            return true;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return false;
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return false;
        }

        List<SysRoleProjectPermission> permissions = sysRoleProjectPermissionMapper.selectByRoleIds(roleIds);
        for (SysRoleProjectPermission perm : permissions) {
            if (perm.getProjectId().equals(projectId) && perm.getPermissionType() >= requiredType) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取当前用户可访问的项目编码集合。
     * 管理员返回null（表示无限制），无权限返回空集合。
     */
    public Set<String> getAccessibleProjectCodes() {
        if (isAdmin()) {
            return null;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }

        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<SysRoleProjectPermission> permissions = sysRoleProjectPermissionMapper.selectByRoleIds(roleIds);
        if (permissions.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> projectIds = permissions.stream()
                .map(SysRoleProjectPermission::getProjectId)
                .collect(Collectors.toSet());

        List<DevProject> projects = devProjectMapper.selectBatchIds(projectIds);
        return projects.stream()
                .map(DevProject::getProjectCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean hasTableOperation(SysTablePermission perm, String operation) {
        return switch (operation.toUpperCase()) {
            case TABLE_OP_SELECT -> perm.getCanSelect() != null && perm.getCanSelect() == 1;
            case TABLE_OP_INSERT -> perm.getCanInsert() != null && perm.getCanInsert() == 1;
            case TABLE_OP_UPDATE -> perm.getCanUpdate() != null && perm.getCanUpdate() == 1;
            case TABLE_OP_DELETE -> perm.getCanDelete() != null && perm.getCanDelete() == 1;
            case TABLE_OP_EXPORT -> perm.getCanExport() != null && perm.getCanExport() == 1;
            default -> false;
        };
    }

    private List<Long> getUserRoleIds(Long userId) {
        Long tenantId = sysTenantUserMapper.selectTenantIdByUserId(userId);
        if (tenantId == null) {
            return Collections.emptyList();
        }
        List<Long> roleIds = sysTenantRoleMapper.selectRoleIdsByTenantId(tenantId);
        return roleIds != null ? roleIds : Collections.emptyList();
    }

    private List<Long> getFilePermissionIdsByRoleIds(List<Long> roleIds) {
        List<SysRoleFilePermission> allBindings = sysRoleFilePermissionMapper.selectByRoleIds(roleIds);
        return allBindings.stream()
                .map(SysRoleFilePermission::getFilePermissionId)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Long> getTablePermissionIdsByRoleIds(List<Long> roleIds) {
        List<SysRoleTablePermission> allBindings = sysRoleTablePermissionMapper.selectByRoleIds(roleIds);
        return allBindings.stream()
                .map(SysRoleTablePermission::getTablePermissionId)
                .distinct()
                .collect(Collectors.toList());
    }
}
