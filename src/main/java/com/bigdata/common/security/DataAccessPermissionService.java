package com.bigdata.common.security;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataAccessPermissionService {

    private static final Logger log = LoggerFactory.getLogger(DataAccessPermissionService.class);

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
                merged.setDataMaskingRule(mergeDataMaskingRule(
                        merged.getDataMaskingRule(), perm.getDataMaskingRule()));
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
     * 合并两条命中权限的脱敏规则。
     * <p>
     * 旧实现是「后一条非空规则整体覆盖前一条」，多角色并集时窄权限配置的脱敏会被宽权限静默覆盖。
     * 现改为按列合并：列取并集，同一列出现多份规则时取更严格的规则（full &gt; idcard/phone &gt; email &gt; partial）。
     * </p>
     * <p>任一侧不是合法 JSON 对象时，退化为「保留已有非空值」，避免合并失败导致脱敏丢失。</p>
     */
    String mergeDataMaskingRule(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> existingMap = mapper.readValue(existing, Map.class);
            Map<String, Object> incomingMap = mapper.readValue(incoming, Map.class);
            Map<String, Object> result = new LinkedHashMap<>(existingMap);
            for (Map.Entry<String, Object> entry : incomingMap.entrySet()) {
                Object current = result.get(entry.getKey());
                result.put(entry.getKey(), current == null
                        ? entry.getValue()
                        : stricterMaskingRule(current, entry.getValue()));
            }
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("合并脱敏规则失败，保留已有规则: {}", e.getMessage());
            return existing;
        }
    }

    private Object stricterMaskingRule(Object left, Object right) {
        return maskingStrictness(right) > maskingStrictness(left) ? right : left;
    }

    private int maskingStrictness(Object rule) {
        String type = maskingTypeOf(rule);
        if (type == null) {
            return 0;
        }
        return switch (type.toLowerCase()) {
            case "full" -> 5;
            case "idcard", "phone" -> 4;
            case "email" -> 3;
            case "partial" -> 2;
            default -> 1;
        };
    }

    @SuppressWarnings("unchecked")
    private String maskingTypeOf(Object rule) {
        if (rule instanceof String) {
            return (String) rule;
        }
        if (rule instanceof Map) {
            Object type = ((Map<String, Object>) rule).get("type");
            return type != null ? type.toString() : "partial";
        }
        return null;
    }

    /**
     * 获取当前用户对指定数据表的行级过滤条件（指定行）。
     * <p>
     * 返回 {@code null} 表示不做行级过滤（所有行可见）。
     * </p>
     * <p>
     * <b>合并规则（交集收紧）</b>：把所有命中该表的权限所配置的行过滤条件用 AND 连接；
     * 某个命中权限未对该表配置行过滤时，只是「该条权限不额外限制」，不会放宽其他权限的限制。
     * 只有当所有命中权限都没有配置行过滤时，才返回 {@code null}。
     * </p>
     * <p>
     * 旧实现是「任一命中权限未配置行过滤即整体放宽为不过滤」，多角色并集时窄权限会被静默覆盖，
     * 已按风险项 R4 改为取交集收紧。
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
                // 该条权限未对该表做行过滤：本权限不额外限制，但不影响其他权限已配置的限制（交集收紧）
                continue;
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
     * 集合内的列名统一为小写。
     * </p>
     * <p>
     * <b>合并规则（交集收紧）</b>：对命中该表且配置了列白名单的权限取交集；
     * 某个命中权限未对该表配置列白名单时，只表示「该条权限不额外限制列」，
     * 不会把其他权限已经收窄的列白名单放宽。只有当所有命中权限都未配置列白名单时，
     * 才返回 {@code null}。
     * </p>
     * <p>
     * 旧实现是「任一命中权限未限制列即整体放宽 + 其余取并集」，多角色并集时窄权限会被静默覆盖，
     * 已按风险项 R4 改为取交集收紧。
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
        Set<String> intersection = null;
        boolean hasBinding = false;
        for (SysTablePermission perm : permissions) {
            if (!matchesTablePermission(perm, datasourceName, databaseName, tableName)) {
                continue;
            }
            List<SysTablePermissionColumn> columnBindings =
                    tablePermissionColumnMapper.selectByPermissionIdAndTable(perm.getId(), tableName);
            if (columnBindings == null || columnBindings.isEmpty()) {
                // 该条权限未对该表限制列：本权限不额外限制，但不放宽其他权限的列白名单（交集收紧）
                continue;
            }
            Set<String> permColumns = new HashSet<>();
            for (SysTablePermissionColumn c : columnBindings) {
                if (c.getColumnName() != null && !c.getColumnName().isBlank()) {
                    permColumns.add(c.getColumnName().trim().toLowerCase());
                }
            }
            if (permColumns.isEmpty()) {
                continue;
            }
            hasBinding = true;
            if (intersection == null) {
                intersection = new HashSet<>(permColumns);
            } else {
                intersection.retainAll(permColumns);
            }
        }
        if (!hasBinding) {
            return null;
        }
        return intersection != null ? intersection : Collections.emptySet();
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

    /**
     * 批量表级权限过滤 —— 面向「一次要判定很多张表」的场景（资产目录树、资产检索）。
     *
     * <p>为什么需要它：{@link #hasTablePermission} 每次调用都会重新查一遍
     * 租户→角色→权限绑定→权限明细，单张表判断没问题，但目录树/检索一次要判定成百上千张表，
     * 逐个调用会退化成 N 次数据库往返。本方法把权限明细<b>只加载一次</b>，
     * 再用与 {@link #hasTablePermission} <b>完全相同</b>的匹配逻辑在内存里判定。</p>
     *
     * <p>语义与 {@link #hasTablePermission} 保持一致，包含「数据源未指定时跳过数据源维度匹配」
     * 与「权限未列 tableNames 视为该库全部表可见」两条既有约定。</p>
     *
     * @param datasourceName 数据源名（可为 null，表示不限定数据源）
     * @param databaseName   库名（可为 null，表示不限定库）
     * @param tableNames     待判定的表名集合
     * @param operation      操作类型，取值见 {@link #TABLE_OP_SELECT} 等常量
     * @return 集合内当前用户可执行该操作的表名子集；无任何权限时返回空集合
     */
    public Set<String> filterAccessibleTables(String datasourceName, String databaseName,
                                              Collection<String> tableNames, String operation) {
        if (tableNames == null || tableNames.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> candidates = new HashSet<>(tableNames);
        if (isAdmin()) {
            return candidates;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return new HashSet<>();
        }
        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return new HashSet<>();
        }
        List<Long> tablePermissionIds = getTablePermissionIdsByRoleIds(roleIds);
        if (tablePermissionIds.isEmpty()) {
            return new HashSet<>();
        }

        List<SysTablePermission> permissions = sysTablePermissionMapper.selectBatchIds(tablePermissionIds);
        Set<String> allowed = new HashSet<>();
        for (SysTablePermission perm : permissions) {
            if (!hasTableOperation(perm, operation)) {
                continue;
            }
            for (String tableName : candidates) {
                if (matchesTablePermission(perm, datasourceName, databaseName, tableName)) {
                    allowed.add(tableName);
                }
            }
        }
        return allowed;
    }

    /**
     * 是否对某个库具备访问能力（目录树里「库」节点是否需要展示）。
     *
     * <p>判定依据是「该库下至少有一条可 SELECT 的权限命中」，与
     * {@link #getAccessibleDatabaseNames} 的区别是：后者会漏掉
     * 「权限未填 tableNames（即整库可见）」这种配置，导致目录树把有权限的库判成无权限。
     * 因此目录树一律用本方法判定库级可见性。</p>
     */
    public boolean hasDatabaseAccess(String datasourceName, String databaseName, String operation) {
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
            if (!hasTableOperation(perm, operation)) {
                continue;
            }
            if (!matchesDatasourceAndDatabase(perm, datasourceName, databaseName)) {
                continue;
            }
            // 权限命中该库即说明该库可见：tableNames 未填表示整库可见，
            // 填了则表示该库下至少有这些表可见 —— 两种情况下「库」节点都不该被隐藏。
            return true;
        }
        return false;
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
