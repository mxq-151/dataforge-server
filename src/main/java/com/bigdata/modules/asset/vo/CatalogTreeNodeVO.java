package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 资产目录树节点。同一份结构服务「引擎视角」与「规范视角」，用 nodeKind 区分语义。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class CatalogTreeNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 节点稳定 key：engine:{datasourceId} / db:{datasourceId}:{db} / table:{tableMetadataId} / layer:{code} / domain:{layer}.{domain} */
    private String key;

    /** 节点显示名 */
    private String label;

    /** 节点语义：datasource / database / table / layer / domain */
    private String nodeKind;

    /** 仅 table 节点有值，用于跳详情 */
    private Long tableMetadataId;

    /** 所属数据源 */
    private Long datasourceId;

    /** 存储引擎编码 */
    private String storageEngine;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 规范视角下由表名解析出的分层编码 */
    private String layerCode;

    /** 规范视角下由表名解析出的业务域编码 */
    private String domainCode;

    /** 该节点下资产数（库 / 分层 / 业务域节点用） */
    private Integer assetCount;

    /** 解析失败等需要治理的情况 */
    private Boolean unclassified;

    /** 子节点 */
    private List<CatalogTreeNodeVO> children;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getNodeKind() { return nodeKind; }
    public void setNodeKind(String nodeKind) { this.nodeKind = nodeKind; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getStorageEngine() { return storageEngine; }
    public void setStorageEngine(String storageEngine) { this.storageEngine = storageEngine; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getLayerCode() { return layerCode; }
    public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public Integer getAssetCount() { return assetCount; }
    public void setAssetCount(Integer assetCount) { this.assetCount = assetCount; }
    public Boolean getUnclassified() { return unclassified; }
    public void setUnclassified(Boolean unclassified) { this.unclassified = unclassified; }
    public List<CatalogTreeNodeVO> getChildren() { return children; }
    public void setChildren(List<CatalogTreeNodeVO> children) { this.children = children; }
}
