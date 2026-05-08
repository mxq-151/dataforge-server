package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.development.entity.DevExecutionEngine;
import com.bigdata.modules.development.mapper.DevExecutionEngineMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DevExecutionEngineService {

    @Autowired
    private DevExecutionEngineMapper devExecutionEngineMapper;

    public List<DevExecutionEngine> getAllEngines() {
        LambdaQueryWrapper<DevExecutionEngine> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevExecutionEngine::getStatus, 1)
               .orderByAsc(DevExecutionEngine::getSortOrder);
        return devExecutionEngineMapper.selectList(wrapper);
    }

    public List<DevExecutionEngine> getEnginesByType(String engineType) {
        LambdaQueryWrapper<DevExecutionEngine> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevExecutionEngine::getEngineType, engineType)
               .eq(DevExecutionEngine::getStatus, 1)
               .orderByAsc(DevExecutionEngine::getSortOrder);
        return devExecutionEngineMapper.selectList(wrapper);
    }

    public DevExecutionEngine getEngineById(Long id) {
        return devExecutionEngineMapper.selectById(id);
    }
}
