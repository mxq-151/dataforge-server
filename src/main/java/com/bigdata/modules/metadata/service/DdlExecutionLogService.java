package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.metadata.entity.DdlExecutionLog;
import com.bigdata.modules.metadata.mapper.DdlExecutionLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class DdlExecutionLogService {

    @Autowired
    private DdlExecutionLogMapper ddlExecutionLogMapper;

    public Page<DdlExecutionLog> getLogList(int current, int size, Long datasourceId, String sqlType, String status) {
        Page<DdlExecutionLog> page = new Page<>(current, size);
        LambdaQueryWrapper<DdlExecutionLog> wrapper = new LambdaQueryWrapper<>();

        if (datasourceId != null) {
            wrapper.eq(DdlExecutionLog::getDatasourceId, datasourceId);
        }
        if (sqlType != null && !sqlType.isEmpty()) {
            wrapper.eq(DdlExecutionLog::getSqlType, sqlType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DdlExecutionLog::getStatus, status);
        }

        wrapper.orderByDesc(DdlExecutionLog::getCreateTime);
        return ddlExecutionLogMapper.selectPage(page, wrapper);
    }

    public void saveLog(DdlExecutionLog log) {
        ddlExecutionLogMapper.insert(log);
    }

    public void updateLog(DdlExecutionLog log) {
        ddlExecutionLogMapper.updateById(log);
    }

    public DdlExecutionLog createLog(Long datasourceId, String databaseName, String sqlContent,
                                      String sqlType, String objectType, String objectName,
                                      Long executeBy, String executeByName) {
        DdlExecutionLog log = new DdlExecutionLog();
        log.setDatasourceId(datasourceId);
        log.setDatabaseName(databaseName);
        log.setSqlContent(sqlContent);
        log.setSqlType(sqlType);
        log.setObjectType(objectType);
        log.setObjectName(objectName);
        log.setStatus("submitted");
        log.setExecuteBy(executeBy);
        log.setExecuteByName(executeByName);
        log.setMetadataUpdated(0);
        log.setCreateTime(new Date());
        ddlExecutionLogMapper.insert(log);
        return log;
    }
}
