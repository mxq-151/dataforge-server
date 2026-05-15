package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.ClusterConfigDTO;
import com.bigdata.modules.devops.entity.ClusterConfig;
import com.bigdata.modules.devops.service.ClusterConfigService;
import com.bigdata.modules.devops.vo.ClusterConfigVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/devops/cluster")
public class ClusterConfigController {

    @Autowired
    private ClusterConfigService clusterConfigService;

    @GetMapping("/list")
    public Result<Page<ClusterConfigVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String clusterName,
            @RequestParam(required = false) String clusterType) {
        Page<ClusterConfig> page = clusterConfigService.getClusterList(current, size, clusterName, clusterType);
        Page<ClusterConfigVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/all")
    public Result<List<ClusterConfigVO>> getAllClusters() {
        List<ClusterConfig> list = clusterConfigService.getAllClusters();
        List<ClusterConfigVO> voList = list.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/{id}")
    public Result<ClusterConfigVO> getById(@PathVariable Long id) {
        ClusterConfig config = clusterConfigService.getClusterById(id);
        return Result.success(toVO(config));
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody ClusterConfigDTO dto) {
        ClusterConfig config = new ClusterConfig();
        BeanUtils.copyProperties(dto, config);
        clusterConfigService.saveCluster(config);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody ClusterConfigDTO dto) {
        ClusterConfig config = new ClusterConfig();
        BeanUtils.copyProperties(dto, config);
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

    private ClusterConfigVO toVO(ClusterConfig entity) {
        ClusterConfigVO vo = new ClusterConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
