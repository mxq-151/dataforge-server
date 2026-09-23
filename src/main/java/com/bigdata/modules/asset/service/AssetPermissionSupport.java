package com.bigdata.modules.asset.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产目录的权限适配层 —— 把「一批表」收敛成「一批当前用户可见的表」。
 *
 * <h3>为什么资产模块不能自己过滤</h3>
 * <p>资产目录是「人」侧找库表认数据的入口，一次要展示成百上千张表。
 * 如果先全量取回再由前端过滤，越权的库名表名<b>已经到达客户端</b>了 ——
 * 这是信息披露，不是权限控制。因此过滤必须发生在服务端，且必须 fail-close：
 * 判定不通过就是不可见，不存在「取不到权限就放行」。</p>
 *
 * <h3>为什么单独抽一层</h3>
 * <p>树、检索、盘点三个入口都要做同一件事，而 {@code hasTablePermission} 是「单表逐次判定」，
 * 逐个调用会把权限查询放大成 N 次数据库往返。这里统一改走
 * {@link DataAccessPermissionService#filterAccessibleTables}（权限明细只加载一次），
 * 并且按「数据源 + 库」分组调用 —— 因为权限匹配规则同时包含数据源维度与库维度，
 * 跨数据源揉成一批会匹配错。</p>
 *
 * @author dataforge
 */
@Component
public class AssetPermissionSupport {

    /** 数据源 id → 数据源名 的单请求内缓存，避免同一批表反复查同一数据源 */
    private final Map<Long, String> datasourceNameCache = new HashMap<>();

    @Autowired
    private DataAccessPermissionService permissionService;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    /** 当前用户是否超管（超管视为无限制，跳过全部过滤，与平台既有语义一致） */
    public boolean isAdmin() {
        return permissionService.isAdmin();
    }

    /** 数据源 id → 名称；查不到返回 null */
    public String datasourceName(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        String cached = datasourceNameCache.get(datasourceId);
        if (cached != null) {
            return cached;
        }
        DatasourceConfig config = datasourceConfigMapper.selectById(datasourceId);
        String name = config == null ? null : config.getDatasourceName();
        if (name != null) {
            datasourceNameCache.put(datasourceId, name);
        }
        return name;
    }

    /** 清空单请求缓存（长生命周期的调用方在批量入口前调用，避免跨请求串味） */
    public void resetCache() {
        datasourceNameCache.clear();
    }

    /**
     * 批量过滤出当前用户可 SELECT 的表。
     *
     * <p>返回的顺序与入参顺序保持一致，调用方可直接按序消费；
     * 入参里的 null 元素会被丢弃。</p>
     */
    public List<TableMetadata> filterSelectable(Collection<TableMetadata> tables) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyList();
        }
        List<TableMetadata> candidates = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (table != null) {
                candidates.add(table);
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        if (isAdmin()) {
            return candidates;
        }

        // 按「数据源名 + 库名」分组：权限匹配规则跨数据源/跨库会串味
        Map<String, List<TableMetadata>> groups = new LinkedHashMap<>();
        for (TableMetadata table : candidates) {
            String dsName = datasourceName(table.getDatasourceId());
            String key = (dsName == null ? "" : dsName) + "\u0000"
                    + (table.getDatabaseName() == null ? "" : table.getDatabaseName());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(table);
        }

        Set<Long> visibleIds = new HashSet<>();
        for (Map.Entry<String, List<TableMetadata>> entry : groups.entrySet()) {
            List<TableMetadata> group = entry.getValue();
            TableMetadata sample = group.get(0);
            String dsName = datasourceName(sample.getDatasourceId());
            String dbName = sample.getDatabaseName();

            Map<String, TableMetadata> byTableName = new LinkedHashMap<>();
            for (TableMetadata table : group) {
                if (table.getTableName() != null) {
                    byTableName.putIfAbsent(table.getTableName(), table);
                }
            }
            Set<String> allowed = permissionService.filterAccessibleTables(
                    dsName, dbName, byTableName.keySet(), DataAccessPermissionService.TABLE_OP_SELECT);
            for (String tableName : allowed) {
                TableMetadata table = byTableName.get(tableName);
                if (table != null) {
                    visibleIds.add(table.getId());
                }
            }
        }

        List<TableMetadata> result = new ArrayList<>(candidates.size());
        for (TableMetadata table : candidates) {
            if (table.getId() != null && visibleIds.contains(table.getId())) {
                result.add(table);
            }
        }
        return result;
    }

    /**
     * 单表 SELECT 权限校验：无权限直接抛 403（fail-close）。
     *
     * <p>用于「按 id 直取详情」这类无法靠列表过滤拦住的入口。</p>
     */
    public void checkSelectable(TableMetadata table) {
        if (table == null) {
            throw new BusinessException(404, "资产不存在");
        }
        String dsName = datasourceName(table.getDatasourceId());
        permissionService.checkTablePermission(
                dsName, table.getDatabaseName(), table.getTableName(),
                DataAccessPermissionService.TABLE_OP_SELECT);
    }

    /**
     * 库级可见性：目录树的「库」节点用。
     *
     * <p>刻意不用 {@code getAccessibleDatabaseNames} —— 那一套会漏掉
     * 「权限未填 tableNames（整库可见）」的配置，把有权限的库判成无权限。</p>
     */
    public boolean hasDatabaseAccess(String dsName, String dbName) {
        return permissionService.hasDatabaseAccess(
                dsName, dbName, DataAccessPermissionService.TABLE_OP_SELECT);
    }
}
