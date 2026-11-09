package com.bigdata.modules.integration.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * 任务需要同步的一张表。
 *
 * <p>任务级只有一份源端连接与一份目标端连接，表级只保留「库.表」与需要排除的字段：
 * 目标标识（Kafka topic / ES 索引 / HDFS 路径 / Hive 表名）由目标端配置的命名模板
 * 按表推导，不再逐表配置。同一任务内 {@code sourceTable} 唯一。</p>
 */
@TableName("integration_task_table")
public class IntegrationTaskTable extends BaseEntity {

    private Long taskId;
    /** 源表（库.表） */
    private String sourceTable;
    /** 排除字段，逗号分隔；为空表示读取全部字段 */
    private String excludeColumns;
    private Integer sortNo;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getExcludeColumns() {
        return excludeColumns;
    }

    public void setExcludeColumns(String excludeColumns) {
        this.excludeColumns = excludeColumns;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
