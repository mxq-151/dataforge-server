package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.development.entity.DevScriptExecution;
import com.bigdata.modules.development.mapper.DevScriptExecutionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DevScriptExecutionService {

    @Autowired
    private DevScriptExecutionMapper devScriptExecutionMapper;

    public Page<DevScriptExecution> getExecutionList(int current, int size, Long scriptId, String status, String executeMode) {
        Page<DevScriptExecution> page = new Page<>(current, size);
        LambdaQueryWrapper<DevScriptExecution> wrapper = new LambdaQueryWrapper<>();
        
        if (scriptId != null) {
            wrapper.eq(DevScriptExecution::getScriptId, scriptId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DevScriptExecution::getStatus, status);
        }
        if (executeMode != null && !executeMode.isEmpty()) {
            wrapper.eq(DevScriptExecution::getExecuteMode, executeMode);
        }
        
        wrapper.orderByDesc(DevScriptExecution::getCreateTime);
        
        return devScriptExecutionMapper.selectPage(page, wrapper);
    }

    public List<DevScriptExecution> getExecutionsByScriptId(Long scriptId) {
        LambdaQueryWrapper<DevScriptExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevScriptExecution::getScriptId, scriptId)
               .orderByDesc(DevScriptExecution::getCreateTime)
               .last("LIMIT 20");
        return devScriptExecutionMapper.selectList(wrapper);
    }

    public DevScriptExecution getExecutionById(Long id) {
        return devScriptExecutionMapper.selectById(id);
    }

    public DevScriptExecution getByExecutionId(String executionId) {
        LambdaQueryWrapper<DevScriptExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevScriptExecution::getExecutionId, executionId);
        return devScriptExecutionMapper.selectOne(wrapper);
    }

    public void saveExecution(DevScriptExecution execution) {
        devScriptExecutionMapper.insert(execution);
    }

    public void updateExecution(DevScriptExecution execution) {
        devScriptExecutionMapper.updateById(execution);
    }
}
