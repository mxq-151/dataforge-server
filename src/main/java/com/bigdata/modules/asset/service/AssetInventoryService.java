package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.asset.vo.AssetInventoryVO;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产盘点 —— 「我们一共有多少数据资产、分布在哪儿、哪些需要治理」。
 *
 * <h3>分层与业务域的聚合为什么在内存里做</h3>
 * <p>分层与业务域不是数据库里的列，而是由表名命名规范实时解析出来的
 * （见 {@link TableNameConventionParser}）。既然解析是纯函数、不入库，
 * 就只能先把表取回来再分组 —— 这也是「规范调整后无需回刷」这一设计的必然代价。
 * 为了把代价压住，取回时只 select 盘点需要的列，不取 location / fingerprint 这类大字段。</p>
 *
 * <h3>按项目盘点的口径</h3>
 * <p>平台目前<b>没有</b>项目与资产的绑定表：表侧的归属维度是数据源，
 * 项目侧只有 {@code dev_project.default_datasource_id}。因此传入 projectCode 时，
 * 盘点范围被收窄为「该项目默认数据源下的资产」。这是一个明确的近似，
 * 不是完整的项目资产台账 —— 如果后续需要精确口径，需要补一张项目↔资产绑定表。
 * 刻意不做「查不到项目就忽略过滤条件」的处理：静默放宽范围比报错更危险。</p>
 *
 * @author dataforge
 */
@Service
public class AssetInventoryService {

    /** IN 子句分片大小：MySQL 的占位符上限是 65535，留足余量 */
    private static final int ID_CHUNK = 1000;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private AssetHealthService healthService;

    /** 盘点概览 */
    public AssetInventoryVO overview(String projectCode) {
        List<TableMetadata> tables = visibleTables(projectCode, false);

        AssetInventoryVO vo = new AssetInventoryVO();
        vo.setTotalTables(tables.size());
        vo.setTotalColumns((int) countColumns(tables));
        vo.setByDatasource(buildDatasourceStats(tables));

        long totalSize = 0L;
        Map<String, AssetInventoryVO.LayerStat> layerStats = new LinkedHashMap<>();
        Map<String, AssetInventoryVO.DomainStat> domainStats = new LinkedHashMap<>();
        int unclassified = 0;

        for (TableMetadata table : tables) {
            long size = table.getSizeBytes() == null ? 0L : table.getSizeBytes();
            totalSize += size;

            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!parsed.parsed()) {
                unclassified++;
            }

            // 解析失败但仍是已知分层的表，也归入该分层 —— 让它在规范视角里可见，
            // 同时靠 unclassifiedCount 单独计数，而不是被藏进「未分类」看不见。
            String layerCode = parsed.layerCode() != null ? parsed.layerCode()
                    : TableNameConventionParser.UNCLASSIFIED_LAYER;
            AssetInventoryVO.LayerStat layerStat = layerStats.computeIfAbsent(layerCode, code -> {
                AssetInventoryVO.LayerStat stat = new AssetInventoryVO.LayerStat();
                stat.setLayerCode(code);
                stat.setTableCount(0);
                stat.setSizeBytes(0L);
                return stat;
            });
            layerStat.setTableCount(layerStat.getTableCount() + 1);
            layerStat.setSizeBytes(layerStat.getSizeBytes() + size);

            if (parsed.domainCode() != null) {
                AssetInventoryVO.DomainStat domainStat = domainStats.computeIfAbsent(
                        parsed.domainCode(), code -> {
                            AssetInventoryVO.DomainStat stat = new AssetInventoryVO.DomainStat();
                            stat.setDomainCode(code);
                            stat.setTableCount(0);
                            stat.setSizeBytes(0L);
                            return stat;
                        });
                domainStat.setTableCount(domainStat.getTableCount() + 1);
                domainStat.setSizeBytes(domainStat.getSizeBytes() + size);
            }
        }

        vo.setTotalSizeBytes(totalSize);
        vo.setUnclassifiedCount(unclassified);

        List<AssetInventoryVO.LayerStat> byLayer = new ArrayList<>(layerStats.values());
        byLayer.sort(Comparator.comparingInt(AssetInventoryVO.LayerStat::getTableCount).reversed());
        vo.setByLayer(byLayer);

        List<AssetInventoryVO.DomainStat> byDomain = new ArrayList<>(domainStats.values());
        byDomain.sort(Comparator.comparingInt(AssetInventoryVO.DomainStat::getTableCount).reversed());
        vo.setByDomain(byDomain);

        return vo;
    }

    /** 治理项清单（按权限过滤后的资产范围统计） */
    public List<AssetHealthIssueVO> health(String projectCode) {
        return healthService.overview(visibleTables(projectCode, true));
    }

    // ==================================================================
    // 可见资产
    // ==================================================================

    /**
     * 取出当前用户可见的表（可选按项目收窄）。
     *
     * @param withOwnerColumns 是否一并取治理判定所需的属性列
     */
    private List<TableMetadata> visibleTables(String projectCode, boolean withOwnerColumns) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDeleted, 0);
        if (withOwnerColumns) {
            wrapper.select(TableMetadata::getId, TableMetadata::getDatasourceId,
                    TableMetadata::getDatabaseName, TableMetadata::getTableName,
                    TableMetadata::getSizeBytes, TableMetadata::getBusinessOwner,
                    TableMetadata::getTableComment, TableMetadata::getLastAccessTime,
                    TableMetadata::getCategoryCode);
        } else {
            wrapper.select(TableMetadata::getId, TableMetadata::getDatasourceId,
                    TableMetadata::getDatabaseName, TableMetadata::getTableName,
                    TableMetadata::getSizeBytes);
        }

        Long datasourceId = resolveProjectDatasource(projectCode);
        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }

        return permissionSupport.filterSelectable(tableMetadataMapper.selectList(wrapper));
    }

    /**
     * 项目编码 → 默认数据源 id。
     *
     * @return null 表示未指定项目（不限定范围）
     * @throws BusinessException 项目不存在或未配置默认数据源
     */
    private Long resolveProjectDatasource(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<DevProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevProject::getProjectCode, projectCode.trim());
        List<DevProject> projects = devProjectMapper.selectList(wrapper);
        if (projects.isEmpty()) {
            throw new BusinessException(404, "项目不存在：" + projectCode);
        }
        Long datasourceId = projects.get(0).getDefaultDatasourceId();
        if (datasourceId == null) {
            throw new BusinessException("项目 " + projectCode
                    + " 未配置默认数据源，无法按项目盘点资产（平台暂无项目↔资产绑定表，只能按默认数据源近似收窄）");
        }
        return datasourceId;
    }

    /** 字段总数：分片 COUNT(*)，避免把字段行整表拉到应用侧 */
    private long countColumns(List<TableMetadata> tables) {
        List<Long> tableIds = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (table.getId() != null) {
                tableIds.add(table.getId());
            }
        }
        if (tableIds.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (int start = 0; start < tableIds.size(); start += ID_CHUNK) {
            List<Long> chunk = tableIds.subList(start, Math.min(start + ID_CHUNK, tableIds.size()));
            // column_metadata 无 deleted 列，这里不能加逻辑删除条件
            LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ColumnMetadata::getTableMetadataId, chunk);
            Long count = columnMetadataMapper.selectCount(wrapper);
            total += count == null ? 0L : count;
        }
        return total;
    }

    private List<AssetInventoryVO.DatasourceStat> buildDatasourceStats(List<TableMetadata> tables) {
        Map<Long, AssetInventoryVO.DatasourceStat> stats = new LinkedHashMap<>();
        for (TableMetadata table : tables) {
            Long datasourceId = table.getDatasourceId();
            Long key = datasourceId == null ? -1L : datasourceId;
            AssetInventoryVO.DatasourceStat stat = stats.computeIfAbsent(key, k -> {
                AssetInventoryVO.DatasourceStat item = new AssetInventoryVO.DatasourceStat();
                item.setDatasourceId(datasourceId);
                item.setDatasourceName(datasourceId == null ? "未关联数据源"
                        : permissionSupport.datasourceName(datasourceId));
                item.setTableCount(0);
                return item;
            });
            stat.setTableCount(stat.getTableCount() + 1);
        }
        List<AssetInventoryVO.DatasourceStat> result = new ArrayList<>(stats.values());
        result.sort(Comparator.comparingInt(AssetInventoryVO.DatasourceStat::getTableCount).reversed());
        return result;
    }
}
