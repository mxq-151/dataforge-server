package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.system.entity.*;
import com.bigdata.modules.system.mapper.*;
import com.bigdata.modules.system.vo.FilePermissionVO;
import com.bigdata.modules.system.vo.MenuPermissionVO;
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
    private SysMenuPermissionMapper menuPermissionMapper;

    @Autowired
    private SysButtonPermissionMapper buttonPermissionMapper;

    @Autowired
    private SysFilePermissionMapper filePermissionMapper;

    @Autowired
    private SysTablePermissionMapper tablePermissionMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysRoleMenuPermissionMapper roleMenuPermissionMapper;

    @Autowired
    private SysRoleFilePermissionMapper roleFilePermissionMapper;

    @Autowired
    private SysRoleTablePermissionMapper roleTablePermissionMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private SysPermissionTableMapper permissionTableMapper;

    public Page<MenuPermissionVO> getMenuPermissionList(int current, int size, String menuName) {
        Page<SysMenuPermission> page = new Page<>(current, size);
        LambdaQueryWrapper<SysMenuPermission> wrapper = new LambdaQueryWrapper<>();
        if (menuName != null && !menuName.isEmpty()) {
            wrapper.like(SysMenuPermission::getMenuName, menuName);
        }
        wrapper.orderByAsc(SysMenuPermission::getSortOrder);
        Page<SysMenuPermission> entityPage = menuPermissionMapper.selectPage(page, wrapper);
        return toMenuPermissionVOPage(entityPage);
    }

    public List<SysMenuPermission> getAllMenuPermissions() {
        LambdaQueryWrapper<SysMenuPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenuPermission::getStatus, 1);
        wrapper.orderByAsc(SysMenuPermission::getSortOrder);
        return menuPermissionMapper.selectList(wrapper);
    }

    public List<SysMenuPermission> getMenuTree() {
        List<SysMenuPermission> allMenus = getAllMenuPermissions();
        return buildMenuTree(allMenus, 0L);
    }

    private List<SysMenuPermission> buildMenuTree(List<SysMenuPermission> menus, Long parentId) {
        return menus.stream()
                .filter(m -> m.getParentId().equals(parentId))
                .peek(m -> m.setChildren(buildMenuTree(menus, m.getId())))
                .toList();
    }

    public MenuPermissionVO saveMenuPermission(SysMenuPermission permission, List<Long> roleIds) {
        if (permission.getStatus() == null) {
            permission.setStatus(1);
        }
        if (permission.getVisible() == null) {
            permission.setVisible(1);
        }
        menuPermissionMapper.insert(permission);
        saveMenuPermissionRoles(permission.getId(), roleIds);
        return toMenuPermissionVO(permission, roleIds);
    }

    public MenuPermissionVO updateMenuPermission(SysMenuPermission permission, List<Long> roleIds) {
        menuPermissionMapper.updateById(permission);
        roleMenuPermissionMapper.deleteByPermissionId(permission.getId());
        saveMenuPermissionRoles(permission.getId(), roleIds);
        return toMenuPermissionVO(permission, roleIds);
    }

    public void deleteMenuPermission(Long id) {
        menuPermissionMapper.deleteById(id);
        roleMenuPermissionMapper.deleteByPermissionId(id);
    }

    public Page<SysButtonPermission> getButtonPermissionList(int current, int size, Long menuPermissionId) {
        Page<SysButtonPermission> page = new Page<>(current, size);
        LambdaQueryWrapper<SysButtonPermission> wrapper = new LambdaQueryWrapper<>();
        if (menuPermissionId != null) {
            wrapper.eq(SysButtonPermission::getMenuPermissionId, menuPermissionId);
        }
        wrapper.orderByAsc(SysButtonPermission::getSortOrder);
        return buttonPermissionMapper.selectPage(page, wrapper);
    }

    public List<SysButtonPermission> getButtonPermissionsByMenuId(Long menuPermissionId) {
        LambdaQueryWrapper<SysButtonPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysButtonPermission::getMenuPermissionId, menuPermissionId);
        wrapper.orderByAsc(SysButtonPermission::getSortOrder);
        return buttonPermissionMapper.selectList(wrapper);
    }

    public void saveButtonPermission(SysButtonPermission permission) {
        buttonPermissionMapper.insert(permission);
    }

    public void updateButtonPermission(SysButtonPermission permission) {
        buttonPermissionMapper.updateById(permission);
    }

    public void deleteButtonPermission(Long id) {
        buttonPermissionMapper.deleteById(id);
    }

    public Page<FilePermissionVO> getFilePermissionList(int current, int size, Long roleId) {
        Page<SysFilePermission> page = new Page<>(current, size);
        LambdaQueryWrapper<SysFilePermission> wrapper = new LambdaQueryWrapper<>();
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
        LambdaQueryWrapper<SysTablePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysTablePermission::getCreateTime);
        Page<SysTablePermission> entityPage = tablePermissionMapper.selectPage(page, wrapper);
        return toTablePermissionVOPage(entityPage);
    }

    public TablePermissionVO saveTablePermission(SysTablePermission permission, List<Long> roleIds, List<Long> tableMetadataIds) {
        if (permission.getCanSelect() == null) permission.setCanSelect(1);
        if (permission.getCanInsert() == null) permission.setCanInsert(0);
        if (permission.getCanUpdate() == null) permission.setCanUpdate(0);
        if (permission.getCanDelete() == null) permission.setCanDelete(0);
        if (permission.getCanExport() == null) permission.setCanExport(0);
        tablePermissionMapper.insert(permission);
        saveTablePermissionRoles(permission.getId(), roleIds);
        savePermissionTables(permission.getId(), tableMetadataIds);
        return toTablePermissionVO(permission, roleIds, tableMetadataIds);
    }

    public TablePermissionVO updateTablePermission(SysTablePermission permission, List<Long> roleIds, List<Long> tableMetadataIds) {
        tablePermissionMapper.updateById(permission);
        roleTablePermissionMapper.deleteByPermissionId(permission.getId());
        saveTablePermissionRoles(permission.getId(), roleIds);
        permissionTableMapper.deleteByPermissionId(permission.getId());
        savePermissionTables(permission.getId(), tableMetadataIds);
        return toTablePermissionVO(permission, roleIds, tableMetadataIds);
    }

    public void deleteTablePermission(Long id) {
        permissionTableMapper.deleteByPermissionId(id);
        roleTablePermissionMapper.deleteByPermissionId(id);
        tablePermissionMapper.deleteById(id);
    }

    @Transactional
    public void assignRolePermissions(Long roleId, List<Long> menuPermissionIds, List<Long> buttonPermissionIds,
                                       List<Long> filePermissionIds, List<Long> tablePermissionIds) {
        deleteRoleAllPermissions(roleId);
        if (menuPermissionIds != null && !menuPermissionIds.isEmpty()) {
            for (Long menuPermId : menuPermissionIds) {
                userRoleMapper.insertRoleMenuPermission(roleId, menuPermId, LocalDateTime.now());
            }
        }
        if (buttonPermissionIds != null && !buttonPermissionIds.isEmpty()) {
            for (Long btnPermId : buttonPermissionIds) {
                userRoleMapper.insertRoleButtonPermission(roleId, btnPermId, LocalDateTime.now());
            }
        }
        if (filePermissionIds != null && !filePermissionIds.isEmpty()) {
            for (Long filePermId : filePermissionIds) {
                userRoleMapper.insertRoleFilePermission(roleId, filePermId, LocalDateTime.now());
            }
        }
        if (tablePermissionIds != null && !tablePermissionIds.isEmpty()) {
            for (Long tablePermId : tablePermissionIds) {
                userRoleMapper.insertRoleTablePermission(roleId, tablePermId, LocalDateTime.now());
            }
        }
    }

    private void deleteRoleAllPermissions(Long roleId) {
        userRoleMapper.deleteRoleMenuPermissions(roleId);
        userRoleMapper.deleteRoleButtonPermissions(roleId);
        userRoleMapper.deleteRoleFilePermissions(roleId);
        userRoleMapper.deleteRoleTablePermissions(roleId);
    }

    public List<Long> getMenuPermissionIdsByRoleId(Long roleId) {
        return userRoleMapper.selectMenuPermissionIdsByRoleId(roleId);
    }

    public List<Long> getButtonPermissionIdsByRoleId(Long roleId) {
        return userRoleMapper.selectButtonPermissionIdsByRoleId(roleId);
    }

    public List<Long> getFilePermissionIdsByRoleId(Long roleId) {
        return userRoleMapper.selectFilePermissionIdsByRoleId(roleId);
    }

    public List<Long> getTablePermissionIdsByRoleId(Long roleId) {
        return userRoleMapper.selectTablePermissionIdsByRoleId(roleId);
    }

    private Map<Long, String> getAllRoleNameMap() {
        List<SysRole> roles = roleMapper.selectList(null);
        return roles.stream().collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
    }

    private MenuPermissionVO toMenuPermissionVO(SysMenuPermission entity, List<Long> roleIds) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        MenuPermissionVO vo = new MenuPermissionVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setRoleIds(roleIds != null ? roleIds : List.of());
        vo.setRoleNames(vo.getRoleIds().stream()
                .map(roleId -> roleNameMap.getOrDefault(roleId, "未知"))
                .collect(Collectors.toList()));
        return vo;
    }

    private Page<MenuPermissionVO> toMenuPermissionVOPage(Page<SysMenuPermission> entityPage) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        Page<MenuPermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(entity -> {
            MenuPermissionVO vo = new MenuPermissionVO();
            BeanUtils.copyProperties(entity, vo);
            List<Long> roleIds = roleMenuPermissionMapper.selectRoleIdsByPermissionId(entity.getId());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(roleId -> roleNameMap.getOrDefault(roleId, "未知"))
                    .collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList()));
        return voPage;
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
        Page<FilePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(entity -> {
            FilePermissionVO vo = new FilePermissionVO();
            BeanUtils.copyProperties(entity, vo);
            List<Long> roleIds = roleFilePermissionMapper.selectRoleIdsByPermissionId(entity.getId());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(rId -> roleNameMap.getOrDefault(rId, "未知"))
                    .collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList()));
        return voPage;
    }

    private TablePermissionVO toTablePermissionVO(SysTablePermission entity, List<Long> roleIds, List<Long> tableMetadataIds) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();
        TablePermissionVO vo = new TablePermissionVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setRoleIds(roleIds != null ? roleIds : List.of());
        vo.setRoleNames(vo.getRoleIds().stream()
                .map(roleId -> roleNameMap.getOrDefault(roleId, "未知"))
                .collect(Collectors.toList()));

        if (tableMetadataIds != null && !tableMetadataIds.isEmpty()) {
            vo.setTableMetadataIds(tableMetadataIds);
            fillTableInfo(vo, tableMetadataIds);
        } else {
            vo.setTableMetadataIds(List.of());
            vo.setTableNames(List.of());
        }
        return vo;
    }

    private Page<TablePermissionVO> toTablePermissionVOPage(Page<SysTablePermission> entityPage) {
        Map<Long, String> roleNameMap = getAllRoleNameMap();

        List<SysTablePermission> records = entityPage.getRecords();

        Set<Long> allTableMetadataIds = new HashSet<>();
        Map<Long, List<Long>> permissionTableMap = new HashMap<>();
        for (SysTablePermission entity : records) {
            List<Long> tIds = permissionTableMapper.selectTableMetadataIdsByPermissionId(entity.getId());
            permissionTableMap.put(entity.getId(), tIds);
            if (tIds != null) {
                allTableMetadataIds.addAll(tIds);
            }
        }

        Map<Long, TableMetadata> tableMetadataMap;
        if (!allTableMetadataIds.isEmpty()) {
            List<TableMetadata> tables = tableMetadataMapper.selectBatchIds(allTableMetadataIds);
            tableMetadataMap = tables.stream()
                    .collect(Collectors.toMap(TableMetadata::getId, t -> t));
        } else {
            tableMetadataMap = new HashMap<>();
        }

        Set<Long> datasourceIds = tableMetadataMap.values().stream()
                .map(TableMetadata::getDatasourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, DatasourceConfig> datasourceMap;
        if (!datasourceIds.isEmpty()) {
            List<DatasourceConfig> datasources = datasourceConfigMapper.selectBatchIds(datasourceIds);
            datasourceMap = datasources.stream()
                    .collect(Collectors.toMap(DatasourceConfig::getId, d -> d));
        } else {
            datasourceMap = new HashMap<>();
        }

        Page<TablePermissionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records.stream().map(entity -> {
            TablePermissionVO vo = new TablePermissionVO();
            BeanUtils.copyProperties(entity, vo);
            List<Long> roleIds = roleTablePermissionMapper.selectRoleIdsByPermissionId(entity.getId());
            vo.setRoleIds(roleIds);
            vo.setRoleNames(roleIds.stream()
                    .map(rId -> roleNameMap.getOrDefault(rId, "未知"))
                    .collect(Collectors.toList()));

            List<Long> tIds = permissionTableMap.getOrDefault(entity.getId(), List.of());
            vo.setTableMetadataIds(tIds);

            if (!tIds.isEmpty()) {
                List<String> tableNames = new ArrayList<>();
                String dbName = null;
                Long dsId = null;
                for (Long tid : tIds) {
                    TableMetadata tm = tableMetadataMap.get(tid);
                    if (tm != null) {
                        tableNames.add(tm.getTableName());
                        if (dbName == null) {
                            dbName = tm.getDatabaseName();
                            dsId = tm.getDatasourceId();
                        }
                    }
                }
                vo.setTableNames(tableNames);
                vo.setDatabaseName(dbName);

                if (dsId != null) {
                    DatasourceConfig ds = datasourceMap.get(dsId);
                    if (ds != null) {
                        vo.setDatasourceName(ds.getDatasourceName());
                    }
                }
            } else {
                vo.setTableNames(List.of());
            }
            return vo;
        }).collect(Collectors.toList()));
        return voPage;
    }

    private void fillTableInfo(TablePermissionVO vo, List<Long> tableMetadataIds) {
        if (tableMetadataIds == null || tableMetadataIds.isEmpty()) return;

        List<TableMetadata> tables = tableMetadataMapper.selectBatchIds(tableMetadataIds);
        if (tables == null || tables.isEmpty()) return;

        List<String> tableNames = tables.stream()
                .map(TableMetadata::getTableName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        vo.setTableNames(tableNames);

        if (!tables.isEmpty()) {
            vo.setDatabaseName(tables.get(0).getDatabaseName());
            Long dsId = tables.get(0).getDatasourceId();
            if (dsId != null) {
                DatasourceConfig ds = datasourceConfigMapper.selectById(dsId);
                if (ds != null) {
                    vo.setDatasourceName(ds.getDatasourceName());
                }
            }
        }
    }

    private void savePermissionTables(Long permissionId, List<Long> tableMetadataIds) {
        if (tableMetadataIds == null || tableMetadataIds.isEmpty()) return;
        for (Long tableMetadataId : tableMetadataIds) {
            permissionTableMapper.insertPermissionTable(permissionId, tableMetadataId, LocalDateTime.now());
        }
    }

    private void saveMenuPermissionRoles(Long permissionId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long roleId : roleIds) {
            SysRoleMenuPermission rp = new SysRoleMenuPermission();
            rp.setRoleId(roleId);
            rp.setMenuPermissionId(permissionId);
            rp.setCreateTime(LocalDateTime.now());
            roleMenuPermissionMapper.insert(rp);
        }
    }

    private void saveFilePermissionRoles(Long permissionId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long roleId : roleIds) {
            SysRoleFilePermission rp = new SysRoleFilePermission();
            rp.setRoleId(roleId);
            rp.setFilePermissionId(permissionId);
            rp.setCreateTime(LocalDateTime.now());
            roleFilePermissionMapper.insert(rp);
        }
    }

    private void saveTablePermissionRoles(Long permissionId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long roleId : roleIds) {
            SysRoleTablePermission rp = new SysRoleTablePermission();
            rp.setRoleId(roleId);
            rp.setTablePermissionId(permissionId);
            rp.setCreateTime(LocalDateTime.now());
            roleTablePermissionMapper.insert(rp);
        }
    }
}
