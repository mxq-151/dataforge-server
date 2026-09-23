package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 治理项。每项都可追到具体对象，不做加权分数。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetHealthIssueVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 治理项编码 */
    private String issueCode;

    /** 治理项名称 */
    private String issueName;

    /** 问题对象数量 */
    private Integer count;

    /** 影响面描述，如「含 12 张核心表」 */
    private String impact;

    /** 样例对象，便于直接跳转处理 */
    private List<Sample> samples;

    public String getIssueCode() { return issueCode; }
    public void setIssueCode(String issueCode) { this.issueCode = issueCode; }
    public String getIssueName() { return issueName; }
    public void setIssueName(String issueName) { this.issueName = issueName; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public List<Sample> getSamples() { return samples; }
    public void setSamples(List<Sample> samples) { this.samples = samples; }

    /**
     * 治理项样例对象。
     */
    public static class Sample implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 表元数据 id */
        private Long tableMetadataId;

        /** 库名 */
        private String databaseName;

        /** 表名 */
        private String tableName;

        public Long getTableMetadataId() { return tableMetadataId; }
        public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
        public String getDatabaseName() { return databaseName; }
        public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
    }
}
