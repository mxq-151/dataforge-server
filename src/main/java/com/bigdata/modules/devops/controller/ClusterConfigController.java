package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.entity.ClusterConfig;
import com.bigdata.modules.devops.service.ClusterConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/devops/cluster")
public class ClusterConfigController {

    @Autowired
    private ClusterConfigService clusterConfigService;

    @GetMapping("/list")
    public Result<Page<ClusterConfig>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String clusterName,
            @RequestParam(required = false) String clusterType) {
        Page<ClusterConfig> result = clusterConfigService.getClusterList(current, size, clusterName, clusterType);
        return Result.success(result);
    }

    @GetMapping("/all")
    public Result<List<ClusterConfig>> getAllClusters() {
        List<ClusterConfig> list = clusterConfigService.getAllClusters();
        return Result.success(list);
    }

    @GetMapping("/{id}")
    public Result<ClusterConfig> getById(@PathVariable Long id) {
        ClusterConfig config = clusterConfigService.getClusterById(id);
        return Result.success(config);
    }

    @PostMapping
    public Result<Void> save(@RequestBody ClusterConfig config) {
        clusterConfigService.saveCluster(config);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody ClusterConfig config) {
        clusterConfigService.updateCluster(config);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        clusterConfigService.deleteCluster(id);
        return Result.success();
    }

    @GetMapping("/types")
    public Result<List<String>> getClusterTypes() {
        List<String> types = clusterConfigService.getClusterTypes();
        return Result.success(types);
    }
}
