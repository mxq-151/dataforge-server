package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.metadata.entity.MetadataSyncLog;
import com.bigdata.modules.metadata.mapper.MetadataSyncLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MetadataSyncLogService {

    @Autowired
    private MetadataSyncLogMapper metadataSyncLogMapper;

    public MetadataSyncLog createSyncLog(Long datasourceId, String syncType, Long createBy) {
        MetadataSyncLog log = new MetadataSyncLog();
        log.setDatasourceId(datasourceId);
        log.setSyncType(syncType);
        log.setStatus("running");
        log.setStartTime(new Date());
        log.setCreateBy(createBy);
        metadataSyncLogMapper.insert(log);
        return log;
    }

    public void completeSyncLog(Long id, int dbCount, int tableCount, int colCount) {
        MetadataSyncLog log = metadataSyncLogMapper.selectById(id);
        if (log != null) {
            log.setStatus("success");
            log.setDatabaseCount(dbCount);
            log.setTableCount(tableCount);
            log.setColumnCount(colCount);
            log.setEndTime(new Date());
            log.setDurationMs((int) (log.getEndTime().getTime() - log.getStartTime().getTime()));
            metadataSyncLogMapper.updateById(log);
        }
    }

    public void failSyncLog(Long id, String errorMessage) {
        MetadataSyncLog log = metadataSyncLogMapper.selectById(id);
        if (log != null) {
            log.setStatus("failed");
            log.setErrorMessage(errorMessage);
            log.setEndTime(new Date());
            if (log.getStartTime() != null) {
                log.setDurationMs((int) (log.getEndTime().getTime() - log.getStartTime().getTime()));
            }
            metadataSyncLogMapper.updateById(log);
        }
    }

    public MetadataSyncLog getLatestSyncLog(Long datasourceId) {
        LambdaQueryWrapper<MetadataSyncLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetadataSyncLog::getDatasourceId, datasourceId)
               .orderByDesc(MetadataSyncLog::getCreateTime)
               .last("LIMIT 1");
        return metadataSyncLogMapper.selectOne(wrapper);
    }
}
