package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.AnnotationGapColumnVO;
import com.bigdata.modules.metadata.vo.AnnotationGapSummaryVO;
import com.bigdata.modules.metadata.vo.AnnotationGapTableVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「未标注表 / 字段」清单（风险项 R1）。
 * <p>
 * 缺口背景：库表与指标描述不完整时，智能体只能靠列名猜语义，口径错误会被放大。
 * 但此前缺失是「不可见」的——需要逐个表点开才能发现没写注释。
 * 本服务把缺失汇总成一份可查询的清单：表注释为空的表、字段注释为空的字段，
 * 并按调用者的表权限过滤（与 {@code /metadata/tables} 保持一致的口径）。
 * </p>
 */
@Service
public class MetadataAnnotationGapService {

    private static final Logger log = LoggerFactory.getLogger(MetadataAnnotationGapService.class);

    /** 一次查询最多分析的列记录批次大小，避免 IN 子句过长 */
    private static final int COLUMN_BATCH_SIZE = 500;

    /** 默认扫描表数量上限 */
    public static final int DEFAULT_LIMIT = 500;
    /** 扫描表数量上限 */
    public static final int MAX_LIMIT = 2000;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    /**
     * 统计表 / 字段注释缺失情况。
     *
     * @param datasourceId 可选，限定数据源
     * @param databaseName 可选，限定库
     * @param limit        扫描表数量上限，null 时取默认值
     */
    public AnnotationGapSummaryVO query(Long datasourceId, String databaseName, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDeleted, 0);
        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(TableMetadata::getDatabaseName, databaseName);
        }
        wrapper.orderByAsc(TableMetadata::getDatabaseName).orderByAsc(TableMetadata::getTableName);
        // 多取一条用于判断是否被 limit 截断
        wrapper.last("LIMIT " + (effectiveLimit + 1));

        List<TableMetadata> scanned = tableMetadataMapper.selectList(wrapper);
        boolean truncated = scanned.size() > effectiveLimit;
        if (truncated) {
            scanned = new ArrayList<>(scanned.subList(0, effectiveLimit));
        }

        List<TableMetadata> visible = filterByPermission(scanned, datasourceId);
        Map<Long, List<ColumnMetadata>> columnsByTable = loadColumns(visible);

        Map<Long, String> datasourceNames = new HashMap<>();
        AnnotationGapSummaryVO summary = new AnnotationGapSummaryVO();
        summary.setTableScanned(visible.size());
        summary.setTruncated(truncated);

        List<AnnotationGapTableVO> gaps = new ArrayList<>();
        int unannotatedTableCount = 0;
        int unannotatedColumnCount = 0;
        for (TableMetadata table : visible) {
            boolean tableCommentMissing = isBlank(table.getTableComment());
            List<ColumnMetadata> columns = columnsByTable.getOrDefault(table.getId(), List.of());
            List<AnnotationGapColumnVO> unannotatedColumns = new ArrayList<>();
            for (ColumnMetadata column : columns) {
                if (isBlank(column.getColumnComment())) {
                    AnnotationGapColumnVO vo = new AnnotationGapColumnVO();
                    vo.setColumnName(column.getColumnName());
                    vo.setColumnType(column.getColumnType());
                    vo.setOrdinalPosition(column.getOrdinalPosition());
                    vo.setIsPartition(column.getIsPartition());
                    unannotatedColumns.add(vo);
                }
            }
            if (!tableCommentMissing && unannotatedColumns.isEmpty()) {
                continue;
            }
            if (tableCommentMissing) {
                unannotatedTableCount++;
            }
            unannotatedColumnCount += unannotatedColumns.size();

            AnnotationGapTableVO vo = new AnnotationGapTableVO();
            vo.setId(table.getId());
            vo.setDatasourceId(table.getDatasourceId());
            vo.setDatasourceName(resolveDatasourceName(table.getDatasourceId(), datasourceNames));
            vo.setStorageEngine(table.getStorageEngine());
            vo.setDatabaseName(table.getDatabaseName());
            vo.setTableName(table.getTableName());
            vo.setTableComment(table.getTableComment());
            vo.setTableCommentMissing(tableCommentMissing);
            vo.setColumnCount(columns.size());
            vo.setUnannotatedColumnCount(unannotatedColumns.size());
            unannotatedColumns.sort(Comparator.comparing(AnnotationGapColumnVO::getOrdinalPosition,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            vo.setUnannotatedColumns(unannotatedColumns);
            gaps.add(vo);
        }

        summary.setUnannotatedTableCount(unannotatedTableCount);
        summary.setUnannotatedColumnCount(unannotatedColumnCount);
        summary.setTables(gaps);
        log.info("未标注元数据清单: 扫描表={}, 表注释缺失={}, 字段注释缺失={}, 截断={}",
                visible.size(), unannotatedTableCount, unannotatedColumnCount, truncated);
        return summary;
    }

    /** 按调用者的库 / 表权限过滤，口径与 /metadata/tables 一致。 */
    private List<TableMetadata> filterByPermission(List<TableMetadata> tables, Long datasourceId) {
        if (dataAccessPermissionService.isAdmin()) {
            return tables;
        }
        String datasourceName = datasourceId != null
                ? resolveDatasourceName(datasourceId, new HashMap<>()) : null;
        Set<String> accessibleDatabases = dataAccessPermissionService.getAccessibleDatabaseNames(datasourceName);
        Map<String, Set<String>> accessibleTablesCache = new HashMap<>();
        return tables.stream().filter(table -> {
            if (accessibleDatabases != null && !accessibleDatabases.isEmpty()
                    && (table.getDatabaseName() == null || !accessibleDatabases.contains(table.getDatabaseName()))) {
                return false;
            }
            if (table.getDatabaseName() == null) {
                return true;
            }
            Set<String> accessibleTables = accessibleTablesCache.computeIfAbsent(table.getDatabaseName(),
                    db -> dataAccessPermissionService.getAccessibleTableNames(datasourceName, db));
            if (accessibleTables == null || accessibleTables.isEmpty()) {
                return true;
            }
            return table.getTableName() != null && accessibleTables.contains(table.getTableName());
        }).collect(Collectors.toList());
    }

    /** 批量加载这些表下的全部字段（分批次，避免 IN 子句过长）。 */
    private Map<Long, List<ColumnMetadata>> loadColumns(List<TableMetadata> tables) {
        List<Long> tableIds = tables.stream()
                .map(TableMetadata::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<ColumnMetadata>> result = new LinkedHashMap<>();
        if (tableIds.isEmpty()) {
            return result;
        }
        for (int start = 0; start < tableIds.size(); start += COLUMN_BATCH_SIZE) {
            List<Long> batch = tableIds.subList(start, Math.min(start + COLUMN_BATCH_SIZE, tableIds.size()));
            LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ColumnMetadata::getTableMetadataId, batch);
            List<ColumnMetadata> columns = columnMetadataMapper.selectList(wrapper);
            for (ColumnMetadata column : columns) {
                result.computeIfAbsent(column.getTableMetadataId(), k -> new ArrayList<>()).add(column);
            }
        }
        return result;
    }

    private String resolveDatasourceName(Long datasourceId, Map<Long, String> cache) {
        if (datasourceId == null) {
            return null;
        }
        String cached = cache.get(datasourceId);
        if (cached != null) {
            return cached;
        }
        DatasourceConfig config = datasourceConfigMapper.selectById(datasourceId);
        String name = config != null ? config.getDatasourceName() : null;
        if (name != null) {
            cache.put(datasourceId, name);
        }
        return name;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
