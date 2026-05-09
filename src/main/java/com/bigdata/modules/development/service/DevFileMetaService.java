package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.development.entity.DevFileMeta;
import com.bigdata.modules.development.mapper.DevFileMetaMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DevFileMetaService {

    private static final Logger log = LoggerFactory.getLogger(DevFileMetaService.class);

    @Autowired
    private DevFileMetaMapper devFileMetaMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public DevFileMeta getByProjectAndFilePath(Long projectId, String filePath) {
        LambdaQueryWrapper<DevFileMeta> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevFileMeta::getProjectId, projectId)
               .eq(DevFileMeta::getFilePath, filePath);
        return devFileMetaMapper.selectOne(wrapper);
    }

    public void saveFileMeta(Long projectId, String filePath, List<String> datasources,
                              List<String> jarDependencyCatalogs, String remark, String engine,
                              Map<String, Object> runParams, Map<String, Object> computeResources) {
        DevFileMeta existing = getByProjectAndFilePath(projectId, filePath);

        String catalogsJson = serializeList(jarDependencyCatalogs);
        String datasourcesJson = serializeList(datasources);
        String runParamsJson = serializeMap(runParams);
        String computeResourcesJson = serializeMap(computeResources);

        if (existing != null) {
            existing.setDatasources(datasourcesJson);
            existing.setJarDependencyCatalogs(catalogsJson);
            existing.setRemark(remark);
            existing.setEngine(engine);
            existing.setRunParams(runParamsJson);
            existing.setComputeResources(computeResourcesJson);
            devFileMetaMapper.updateById(existing);
            log.info("更新文件元数据: projectId={}, filePath={}", projectId, filePath);
        } else {
            DevFileMeta meta = new DevFileMeta();
            meta.setProjectId(projectId);
            meta.setFilePath(filePath);
            meta.setDatasources(datasourcesJson);
            meta.setJarDependencyCatalogs(catalogsJson);
            meta.setRemark(remark);
            meta.setEngine(engine);
            meta.setRunParams(runParamsJson);
            meta.setComputeResources(computeResourcesJson);
            devFileMetaMapper.insert(meta);
            log.info("保存文件元数据: projectId={}, filePath={}", projectId, filePath);
        }
    }

    public void deleteFileMeta(Long projectId, String filePath) {
        DevFileMeta existing = getByProjectAndFilePath(projectId, filePath);
        if (existing != null) {
            devFileMetaMapper.deleteById(existing.getId());
            log.info("删除文件元数据: projectId={}, filePath={}", projectId, filePath);
        }
    }

    private String serializeList(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("序列化列表失败", e);
            return null;
        }
    }

    private String serializeMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("序列化Map失败", e);
            return null;
        }
    }
}
