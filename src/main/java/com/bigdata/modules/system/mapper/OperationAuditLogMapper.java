package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.OperationAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface OperationAuditLogMapper extends BaseMapper<OperationAuditLog> {

    @Select("SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_audit_log' " +
            "AND PARTITION_NAME != 'p_max' " +
            "AND TO_DAYS(PARTITION_DESCRIPTION) <= TO_DAYS(#{beforeDate}) " +
            "ORDER BY PARTITION_ORDINAL_POSITION ASC")
    List<String> selectExpiredPartitions(@Param("beforeDate") String beforeDate);

    @Update({
        "SET @sql = CONCAT('ALTER TABLE operation_audit_log DROP PARTITION ', #{partitionName})",
        "PREPARE stmt FROM @sql",
        "EXECUTE stmt",
        "DEALLOCATE PREPARE stmt"
    })
    int dropPartition(@Param("partitionName") String partitionName);
}
