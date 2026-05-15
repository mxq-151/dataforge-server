package com.bigdata.modules.devops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.mapper.StorageConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigdata.modules.devops.vo.StorageConnectionTestVO;

import java.util.List;

@Service
public class StorageConfigService {

    @Autowired
    private StorageConfigMapper storageConfigMapper;

    @Autowired
    FileExplorerService fileExplorerService;

    public Page<StorageConfig> getStorageList(int current, int size, String storageName, String storageType) {
        Page<StorageConfig> page = new Page<>(current, size);
        LambdaQueryWrapper<StorageConfig> wrapper = new LambdaQueryWrapper<>();
        if (storageName != null && !storageName.isEmpty()) {
            wrapper.like(StorageConfig::getStorageName, storageName);
        }
        if (storageType != null && !storageType.isEmpty()) {
            wrapper.eq(StorageConfig::getStorageType, storageType);
        }
        wrapper.orderByDesc(StorageConfig::getCreateTime);
        return storageConfigMapper.selectPage(page, wrapper);
    }

    public List<StorageConfig> getAllStorages() {
        LambdaQueryWrapper<StorageConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageConfig::getStatus, 1);
        wrapper.orderByAsc(StorageConfig::getId);
        return storageConfigMapper.selectList(wrapper);
    }

    public StorageConfig getStorageById(Long id) {
        return storageConfigMapper.selectById(id);
    }

    public void saveStorage(StorageConfig config) {
        config.setStatus(1);
        storageConfigMapper.insert(config);
    }

    public void updateStorage(StorageConfig config) {
        storageConfigMapper.updateById(config);
    }

    public void deleteStorage(Long id) {
        StorageConfig config = storageConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("存储配置不存在");
        }
        storageConfigMapper.deleteById(id);
    }

    public StorageConnectionTestVO testConnection(Long id) {
        StorageConfig config = storageConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("存储配置不存在");
        }
        boolean success = this.fileExplorerService.testConnection(config.getStorageType(), id);

        String message = "";
        long startTime = System.currentTimeMillis();

        try {
            switch (config.getStorageType().toLowerCase()) {
                case "hdfs":
                    message = success ? "HDFS连接成功" : "HDFS连接失败";
                    break;
                case "s3":
                    message = success ? "S3连接成功" : "S3连接失败";
                    break;
                case "juicefs":
                    message = success ? "JuiceFS连接成功" : "JuiceFS连接失败";
                    break;
                default:
                    message = "不支持的存储类型: " + config.getStorageType();
            }
        } catch (Exception e) {
            message = "连接异常: " + e.getMessage();
        }

        if (success) {
            config.setStatus(1);
        } else {
            config.setStatus(2);
        }
        storageConfigMapper.updateById(config);

        return new StorageConnectionTestVO(success, message, config.getStorageType());
    }

    public List<String> getStorageTypes() {
        return List.of("hdfs", "s3", "juicefs");
    }
}
