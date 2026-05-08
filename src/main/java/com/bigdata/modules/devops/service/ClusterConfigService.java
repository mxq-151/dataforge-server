package com.bigdata.modules.devops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.ClusterConfig;
import com.bigdata.modules.devops.mapper.ClusterConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClusterConfigService {

    @Autowired
    private ClusterConfigMapper clusterConfigMapper;

    public Page<ClusterConfig> getClusterList(int current, int size, String clusterName, String clusterType) {
        Page<ClusterConfig> page = new Page<>(current, size);
        LambdaQueryWrapper<ClusterConfig> wrapper = new LambdaQueryWrapper<>();
        if (clusterName != null && !clusterName.isEmpty()) {
            wrapper.like(ClusterConfig::getClusterName, clusterName);
        }
        if (clusterType != null && !clusterType.isEmpty()) {
            wrapper.eq(ClusterConfig::getClusterType, clusterType);
        }
        wrapper.orderByDesc(ClusterConfig::getCreateTime);
        return clusterConfigMapper.selectPage(page, wrapper);
    }

    public List<ClusterConfig> getAllClusters() {
        LambdaQueryWrapper<ClusterConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ClusterConfig::getStatus, 1);
        wrapper.orderByAsc(ClusterConfig::getId);
        return clusterConfigMapper.selectList(wrapper);
    }

    public ClusterConfig getClusterById(Long id) {
        return clusterConfigMapper.selectById(id);
    }

    public void saveCluster(ClusterConfig config) {
        config.setStatus(1);
        clusterConfigMapper.insert(config);
    }

    public void updateCluster(ClusterConfig config) {
        clusterConfigMapper.updateById(config);
    }

    public void deleteCluster(Long id) {
        ClusterConfig config = clusterConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("集群配置不存在");
        }
        clusterConfigMapper.deleteById(id);
    }

    public List<String> getClusterTypes() {
        return List.of("yarn", "k8s");
    }
}
