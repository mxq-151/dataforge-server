package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.OperationAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OperationAuditLogMapper extends BaseMapper<OperationAuditLog> {

    /** 方言见 mapper/mysql/OperationAuditLogMapper.xml */
    List<String> selectExpiredPartitions(@Param("beforeDate") String beforeDate);

    /** 方言见 mapper/mysql/OperationAuditLogMapper.xml */
    int dropPartition(@Param("partitionName") String partitionName);

    /** 查询 operation_audit_log 表的全部分区名（AuditLogPartitionTask 预创建分区用） */
    List<String> selectAllPartitionNames();

    /** 将 p_max 拆分为多个分区 + 新的 p_max（partitionDefs 为逗号拼接的分区定义片段，仅允许 ${} 注入） */
    int createPartitions(@Param("partitionDefs") String partitionDefs);

    /** 一次删除多个分区（partitionNames 为逗号拼接的分区名，仅允许 ${} 注入） */
    int dropPartitions(@Param("partitionNames") String partitionNames);

    /**
     * PostgreSQL 方言：创建单个子分区表（CREATE TABLE ... PARTITION OF）。
     * partitionName 为标识符仅允许 ${}，fromDate/toDate 为边界值用 #{} 绑定。
     */
    int createPartition(@Param("partitionName") String partitionName,
                        @Param("fromDate") String fromDate,
                        @Param("toDate") String toDate);
}