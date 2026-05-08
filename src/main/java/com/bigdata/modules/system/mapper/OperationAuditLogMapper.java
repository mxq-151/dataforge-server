package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.OperationAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationAuditLogMapper extends BaseMapper<OperationAuditLog> {
}
