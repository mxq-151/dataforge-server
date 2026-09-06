package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.bigdata.modules.system.vo.FilePermissionVO;
import com.bigdata.modules.system.vo.TablePermissionVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    @Autowired
    private SysFilePermissionMapper filePermissionMapper;

    @Autowired
    private SysTablePermissionMapper tablePermissionMapper;

    @Autowired
    private SysTablePermissionColumnMapper tablePermissionColumnMapper;

    @Autowired
    private SysTablePermissionRowMapper tablePermissionRowMapper;

    @Autowired
    private SysRoleMenuPermissionMapper roleMenuPermissionMapper;

    @Autowired
    private SysRoleFilePermissionMapper roleFilePermissionMapper;

    @Autowired
    private SysRoleTablePermissionMapper roleTablePermissionMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    public Page<FilePermissionVO> getFilePermissionList(int current, int size, Long roleId) {
        Page<SysFilePermission> page = new Page<>(current, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysFilePermission> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysFilePermission::getCreateTime);
        Page<SysFilePermission> entityPage = filePermissionMapper.selectPage(page, wrapper);
        return toFilePermissionVOPage(entityPage);
    }

    public FilePermissionVO saveFilePermission(SysFilePermission permission, List<Long> roleIds) {
        filePermissionMapper.insert(permission);
        saveFilePermissionRoles(permission.getId(), roleIds);
        return toFilePermissionVO(permission, roleIds);
    }

    public FilePermissionVO updateFilePermission(SysFilePermission permission, List<Long> roleIds) {
        filePermissionMapper.updateById(permission);
        roleFilePermissionMapper.deleteByPermissionId(permission.getId());
        saveFilePermissionRoles(permission.getId(), roleIds);
        return toFilePermissionVO(permission, roleIds);
    }

    public void deleteFilePermission(Long id) {
        filePermissionMapper.deleteById(id);
        roleFilePermissionMapper.deleteByPermissionId(id);
    }

    public Page<TablePermissionVO> getTablePermissionList(int current, int size, Long roleId) {
        Page<SysTablePermission> page = new Page<>(current, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysTablePermission> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysTablePermission::getCreateTime);
        Page<SysTablePermission> entityPage = tablePermissionMapper.selectPage(page, wrapper);
        return toTablePermissionVOPage(entityPage);
    }

    @Transactional
    public TablePermissionVO saveTablePermission(SysTablePermission permission, List<Long> roleIds,
                                                 Map<String, List<String>> columnPermissions,
                                                 Map<String, String> rowFilterSql) {
        if (permission.getCanSelect() == null) permission.setCanSelect(1);
        if (permission.getCanInsert() == null) permission.setCanInsert(0);
        if (permission.getCanUpdate() == null) permission.setCanUpdate(0);
        if (permission.getCanDelete() == null) permission.setCanDelete(0);
        if (permission.getCanExport() == null) permission.setCanExport(0);
        tablePermissionMapper.insert(permission);
        saveTablePermissionRoles(permission.getId(), roleIds);
        saveTablePermissionColumns(permission.getId(), columnPermissions);
        saveTablePermissionRows(permission.getId(), rowFilterSql);
        TablePermissionVO vo = toTablePermissionVO(permission, roleIds);
        vo.setColumnPermissions(columnPermissions);
        vo.setRowFilterSql(rowFilterSql);
        return vo;
    }

    @Transactional
    public TablePermissionVO updateTablePermission(SysTablePermission permission, List<Long> roleIds,
                                                   Map<String, List<String>> columnPermissions,
                                                   Map<String, String> rowFilterSql) {
        tablePermissionMapper.updateById(permission);
        roleTablePermissionMapper.deleteByPermissionId(permission.getId());
        saveTablePermissionRoles(permission.getId(), roleIds);
        tablePermissionColumnMapper.deleteByPermissionId(permission.getId());
        saveTablePermissionColumns(permission.getId(), columnPermissions);
        tablePermissionRowMapper.deleteByPermissionId(permission.getId());
        saveTablePermissionRows(permission.getId(), rowFilterSql);
        TablePermissionVO vo = toTablePermissionVO(permission, roleIds);
        vo.setColumnPermissions(columnPermissions);
        vo.setRowFilterSql(rowFilterSql);
        return vo;
    }

    public void deleteTablePermission(Long id) {
        roleTablePermissionMapper.deleteByPermissionId(id);
        tablePermissionColumnMapper.deleteByPermissionId(id);
        tablePermissionRowMapper.deleteByPermissionId(id);
        tablePermissionMapper.deleteById(id);
    }

    @Transactional
    public void assignRolePermissions(Long roleId, List<Long> menuPermissionIds,
                                       List<Long> filePermissionIds, List<Long> tablePermissionIds) {
        deleteRoleAllPermissions(roleId);
        LocalDateTime now = LocalDateTime.now();
        if (menuPermissionIds != null && !menuPermissionIds.isEmpty()) {
            roleMenuPermissionMapper.batchInsertRoleMenuPermissions(roleId, menuPermissionIds, now);
        }
        if (filePermissionIds != null && !filePermissionIds.isEmpty()) {
            roleFilePermissionMapper.batchInsertRoleFilePermissions(roleId, filePermissionIds, now);
        }
        if (tablePermissionIds != null && !tablePermissionIds.isEmpty()) {
            roleTablePermissionMapper.batchInsertRoleTablePermissions(roleId, tablePermissionIds, now);
        }
    }

    private void deleteRoleAllPermissions(Long roleId) {
        roleMenuPermissionMapper.deleteByRoleId(roleId);
        roleFilePermissionMapper.deleteByRoleId(roleId);
        roleTablePermissionMapper.deleteByRoleId(roleId);
    }

    public List<Long> getMenuPermissionIdsByRoleId(Long roleId) {
        return roleMenuPermissionMapper.selectMenuPermissionIdsByRoleId(roleId);
    }

    public List<Long> getFilePermissionIdsByRoleId(Long roleId) {
        return roleFilePermissionMapper.selectFilePermissionIdsByRoleId(roleId);
    }

    public List<Long> getTablePermissionIdsByRoleId(Long roleId) {
        return roleTablePermissionMapper.selectTablePermissionIdsByRoleId(roleId);
    }

    private Map<Long, String> getAllRoleNameMap() {
        List<SysRole> roles = roleMapper.selectList(null);
        return roles.stream().collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
    }

    private FilePermissionVO toFilePermissionVO(SysFilePermission entity, List<Long> roleIds) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        FilePermissionVO vo = new FilePermissionVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setRoleIds(roleIds != null ? roleIds : List.of());
        vo.setRoleNames(vo.getRoleIds().stream()
                .map(roleId -> roleNameMap.getOrDefault(roleId, "未知"))
                .collect(Collectors.toList()));
        return vo;
    }

    private Page<FilePermissionVO> toFilePermissionVOPage(Page<SysFilePermission> entityPage) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        List<SysFilePermission> records = entityPage.getRecords();
        if (records.isEmpty()) {
            Page<FilePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
            voPage.setRecords(List.of());
            return voPage;
        }

        List<Long> permissionIds = records.stream().map(SysFilePermission::getId).collect(Collectors.toList());
        List<SysRoleFilePermission> allBindings = roleFilePermissionMapper.selectByPermissionIds(permissionIds);
        Map<Long, List<Long>> permRoleMap = allBindings.stream()
                .filter(b -> b.getFilePermissionId() != null && b.getRoleId() != null)
                .collect(Collectors.groupingBy(
                        SysRoleFilePermission::getFilePermissionId,
                        Collectors.mapping(SysRoleFilePermission::getRoleId, Collectors.toList())
                ));

        Page<FilePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records.stream().map(entity -> {
            FilePermissionVO vo = new FilePermissionVO();
            BeanUtils.copyProperties(entity, vo);
            List<Long> roleIds = permRoleMap.getOrDefault(entity.getId(), List.of());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(rId -> roleNameMap.getOrDefault(rId, "未知"))
                    .collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList()));
        return voPage;
    }

    private TablePermissionVO toTablePermissionVO(SysTablePermission entity, List<Long> roleIds) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        TablePermissionVO vo = new TablePermissionVO();
        vo.setId(entity.getId());
        vo.setPermissionName(entity.getPermissionName());
        vo.setDatasourceName(entity.getDatasourceName());
        vo.setDatabaseName(entity.getDatabaseName());
        vo.setPermissionScope(entity.getPermissionScope());
        vo.setCanSelect(entity.getCanSelect());
        vo.setCanInsert(entity.getCanInsert());
        vo.setCanUpdate(entity.getCanUpdate());
        vo.setCanDelete(entity.getCanDelete());
        vo.setCanExport(entity.getCanExport());
        vo.setDataMaskingRule(entity.getDataMaskingRule());
        vo.setDescription(entity.getDescription());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());

        if (entity.getTableNames() != null && !entity.getTableNames().isEmpty()) {
            vo.setTableNames(Arrays.asList(entity.getTableNames().split(",")));
        } else {
            vo.setTableNames(List.of());
        }

        vo.setRoleIds(roleIds != null ? roleIds : List.of());
        vo.setRoleNames(vo.getRoleIds().stream()
                .map(roleId -> roleNameMap.getOrDefault(roleId, "未知"))
                .collect(Collectors.toList()));
        return vo;
    }

    private Page<TablePermissionVO> toTablePermissionVOPage(Page<SysTablePermission> entityPage) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();

        List<SysTablePermission> records = entityPage.getRecords();
        if (records.isEmpty()) {
            Page<TablePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
            voPage.setRecords(List.of());
            return voPage;
        }

        List<Long> permissionIds = records.stream().map(SysTablePermission::getId).collect(Collectors.toList());

        List<SysRoleTablePermission> allRoleBindings = roleTablePermissionMapper.selectByPermissionIds(permissionIds);
        Map<Long, List<Long>> permRoleMap = allRoleBindings.stream()
                .filter(b -> b.getTablePermissionId() != null && b.getRoleId() != null)
                .collect(Collectors.groupingBy(
                        SysRoleTablePermission::getTablePermissionId,
                        Collectors.mapping(SysRoleTablePermission::getRoleId, Collectors.toList())
                ));

        List<SysTablePermissionColumn> allColumnBindings = tablePermissionColumnMapper.selectByPermissionIds(permissionIds);
        Map<Long, List<SysTablePermissionColumn>> permColumnMap = allColumnBindings.stream()
                .filter(c -> c.getTablePermissionId() != null)
                .collect(Collectors.groupingBy(SysTablePermissionColumn::getTablePermissionId));

        List<SysTablePermissionRow> allRowBindings = tablePermissionRowMapper.selectByPermissionIds(permissionIds);
        Map<Long, List<SysTablePermissionRow>> permRowMap = allRowBindings.stream()
                .filter(r -> r.getTablePermissionId() != null)
                .collect(Collectors.groupingBy(SysTablePermissionRow::getTablePermissionId));

        Page<TablePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records.stream().map(entity -> {
            TablePermissionVO vo = new TablePermissionVO();
            vo.setId(entity.getId());
            vo.setPermissionName(entity.getPermissionName());
            vo.setDatasourceName(entity.getDatasourceName());
            vo.setDatabaseName(entity.getDatabaseName());
            vo.setPermissionScope(entity.getPermissionScope());
            vo.setCanSelect(entity.getCanSelect());
            vo.setCanInsert(entity.getCanInsert());
            vo.setCanUpdate(entity.getCanUpdate());
            vo.setCanDelete(entity.getCanDelete());
            vo.setCanExport(entity.getCanExport());
            vo.setDataMaskingRule(entity.getDataMaskingRule());
            vo.setDescription(entity.getDescription());
            vo.setCreateTime(entity.getCreateTime());
            vo.setUpdateTime(entity.getUpdateTime());

            if (entity.getTableNames() != null && !entity.getTableNames().isEmpty()) {
                vo.setTableNames(Arrays.asList(entity.getTableNames().split(",")));
            } else {
                vo.setTableNames(List.of());
            }

            vo.setColumnPermissions(groupColumnPermissions(
                    permColumnMap.getOrDefault(entity.getId(), List.of())));
            vo.setRowFilterSql(groupRowFilterSql(
                    permRowMap.getOrDefault(entity.getId(), List.of())));

            List<Long> roleIds = permRoleMap.getOrDefault(entity.getId(), List.of());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(rId -> roleNameMap.getOrDefault(rId, "未知"))
                    .collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList()));
        return voPage;
    }

    private void saveFilePermissionRoles(Long permissionId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        List<SysRoleFilePermission> list = new ArrayList<>(roleIds.size());
        for (Long roleId : roleIds) {
            SysRoleFilePermission rp = new SysRoleFilePermission();
            rp.setRoleId(roleId);
            rp.setFilePermissionId(permissionId);
            rp.setCreateTime(now);
            list.add(rp);
        }
        roleFilePermissionMapper.batchInsert(list);
    }

    private void saveTablePermissionRoles(Long permissionId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        List<SysRoleTablePermission> list = new ArrayList<>(roleIds.size());
        for (Long roleId : roleIds) {
            SysRoleTablePermission rp = new SysRoleTablePermission();
            rp.setRoleId(roleId);
            rp.setTablePermissionId(permissionId);
            rp.setCreateTime(now);
            list.add(rp);
        }
        roleTablePermissionMapper.batchInsert(list);
    }

    private void saveTablePermissionColumns(Long permissionId, Map<String, List<String>> columnPermissions) {
        if (columnPermissions == null || columnPermissions.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        List<SysTablePermissionColumn> list = new ArrayList<>();
        columnPermissions.forEach((tableName, columns) -> {
            if (tableName == null || columns == null || columns.isEmpty()) return;
            for (String column : columns) {
                if (column == null || column.isBlank()) continue;
                SysTablePermissionColumn c = new SysTablePermissionColumn();
                c.setTablePermissionId(permissionId);
                c.setTableName(tableName.trim());
                c.setColumnName(column.trim());
                c.setCreateTime(now);
                list.add(c);
            }
        });
        if (!list.isEmpty()) {
            tablePermissionColumnMapper.batchInsert(list);
        }
    }

    private Map<String, List<String>> groupColumnPermissions(List<SysTablePermissionColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (SysTablePermissionColumn c : columns) {
            grouped.computeIfAbsent(c.getTableName(), k -> new LinkedHashSet<>()).add(c.getColumnName());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((table, cols) -> result.put(table, new ArrayList<>(cols)));
        return result;
    }

    private void saveTablePermissionRows(Long permissionId, Map<String, String> rowFilterSql) {
        if (rowFilterSql == null || rowFilterSql.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        List<SysTablePermissionRow> list = new ArrayList<>();
        rowFilterSql.forEach((tableName, filterSql) -> {
            if (tableName == null || tableName.isBlank() || filterSql == null || filterSql.isBlank()) return;
            SysTablePermissionRow r = new SysTablePermissionRow();
            r.setTablePermissionId(permissionId);
            r.setTableName(tableName.trim());
            r.setRowFilterSql(filterSql.trim());
            r.setCreateTime(now);
            list.add(r);
        });
        if (!list.isEmpty()) {
            tablePermissionRowMapper.batchInsert(list);
        }
    }

    private Map<String, String> groupRowFilterSql(List<SysTablePermissionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (SysTablePermissionRow r : rows) {
            if (r.getRowFilterSql() != null && !r.getRowFilterSql().isBlank()) {
                result.putIfAbsent(r.getTableName(), r.getRowFilterSql());
            }
        }
        return result.isEmpty() ? null : result;
    }
}
