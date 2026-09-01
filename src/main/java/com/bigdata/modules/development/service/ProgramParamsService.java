package com.bigdata.modules.development.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.dto.ProgramParamsDTO;
import com.bigdata.modules.development.entity.ProgramParams;
import com.bigdata.modules.development.mapper.ProgramParamsMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProgramParamsService {

    private static final Logger log = LoggerFactory.getLogger(ProgramParamsService.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProgramParamsMapper programParamsMapper;

    public ProgramParams getByProjectIdAndFilePath(Long projectId, String filePath) {
        return programParamsMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProgramParams>()
                        .eq(ProgramParams::getProjectId, projectId)
                        .eq(ProgramParams::getFilePath, filePath)
        );
    }

    public List<ProgramParams> listByProjectId(Long projectId) {
        return programParamsMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProgramParams>()
                        .eq(ProgramParams::getProjectId, projectId)
                        .orderByAsc(ProgramParams::getFilePath)
        );
    }

    /**
     * 保存或更新程序参数（upsert）：
     * 由数据库原生 SQL 按联合主键 (project_id, file_path) 实现原子 upsert，
     * 存在则更新，不存在则插入，避免先查后写的两段式逻辑与并发竞态。
     */
    @Transactional
    public void upsert(ProgramParamsDTO dto) {
        ProgramParams entity = convertToEntity(dto);
        programParamsMapper.upsert(entity);
        log.info("程序参数upsert成功: projectId={}, filePath={}", dto.getProjectId(), dto.getFilePath());
    }

    @Transactional
    public void delete(Long projectId, String filePath) {
        int rows = programParamsMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProgramParams>()
                        .eq(ProgramParams::getProjectId, projectId)
                        .eq(ProgramParams::getFilePath, filePath));
        if (rows == 0) {
            throw new BusinessException("未找到对应的程序参数记录");
        }
        log.info("程序参数删除成功: projectId={}, filePath={}", projectId, filePath);
    }

    private ProgramParams convertToEntity(ProgramParamsDTO dto) {
        ProgramParams entity = new ProgramParams();
        entity.setProjectId(dto.getProjectId());
        entity.setFilePath(dto.getFilePath());
        entity.setEngine(dto.getEngine());
        entity.setRemark(dto.getRemark());

        try {
            if (dto.getDatasources() != null && !dto.getDatasources().isEmpty()) {
                entity.setDatasources(objectMapper.writeValueAsString(dto.getDatasources()));
            }
            if (dto.getJarDependencyCatalogs() != null && !dto.getJarDependencyCatalogs().isEmpty()) {
                entity.setJarDependencyCatalogs(objectMapper.writeValueAsString(dto.getJarDependencyCatalogs()));
            }
            if (dto.getRunParams() != null && !dto.getRunParams().isEmpty()) {
                entity.setRunParams(objectMapper.writeValueAsString(dto.getRunParams()));
            }
            if (dto.getComputeResources() != null && !dto.getComputeResources().isEmpty()) {
                entity.setComputeResources(objectMapper.writeValueAsString(dto.getComputeResources()));
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON序列化失败: " + e.getMessage());
        }

        return entity;
    }
}
