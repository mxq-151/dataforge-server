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

    @Transactional
    public void save(ProgramParamsDTO dto) {
        ProgramParams existing = getByProjectIdAndFilePath(dto.getProjectId(), dto.getFilePath());
        if (existing != null) {
            throw new BusinessException("该项目的文件运行参数已存在，请使用更新接口");
        }

        ProgramParams entity = convertToEntity(dto);
        programParamsMapper.insert(entity);
        log.info("程序参数保存成功: projectId={}, filePath={}", dto.getProjectId(), dto.getFilePath());
    }

    @Transactional
    public void update(ProgramParamsDTO dto) {
        ProgramParams existing = getByProjectIdAndFilePath(dto.getProjectId(), dto.getFilePath());
        if (existing == null) {
            throw new BusinessException("未找到对应的程序参数记录");
        }

        ProgramParams entity = convertToEntity(dto);
        entity.setId(existing.getId());

        int rows = programParamsMapper.updateById(entity);
        if (rows == 0) {
            throw new BusinessException("更新失败");
        }
        log.info("程序参数更新成功: projectId={}, filePath={}", dto.getProjectId(), dto.getFilePath());
    }

    @Transactional
    public void delete(Long projectId, String filePath) {
        ProgramParams existing = getByProjectIdAndFilePath(projectId, filePath);
        if (existing == null) {
            throw new BusinessException("未找到对应的程序参数记录");
        }
        programParamsMapper.deleteById(existing.getId());
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
