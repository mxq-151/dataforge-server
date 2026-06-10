package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.metadata.dto.IndicatorDTO;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.IndicatorField;
import com.bigdata.modules.metadata.service.IndicatorService;
import com.bigdata.modules.metadata.vo.IndicatorFieldVO;
import com.bigdata.modules.metadata.vo.IndicatorVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata/indicators")
public class IndicatorController {

    @Autowired
    private IndicatorService indicatorService;

    @GetMapping("/list")
    public Result<Page<IndicatorVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String keyword) {
        Page<Indicator> page = indicatorService.getIndicatorList(current, size, projectName, topic, name, keyword);
        Page<IndicatorVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/filter")
    public Result<List<IndicatorVO>> filterIndicators(
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String keyword) {
        List<Indicator> list = indicatorService.getIndicatorsByFilter(projectName, topic, keyword);
        List<IndicatorVO> voList = list.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/{id}")
    public Result<IndicatorVO> getById(@PathVariable Long id) {
        Indicator indicator = indicatorService.getIndicatorById(id);
        if (indicator == null) {
            return Result.error(404, "指标不存在");
        }
        IndicatorVO vo = toVO(indicator);
        List<IndicatorField> fields = indicatorService.getFieldsByIndicatorId(id);
        vo.setFields(fields.stream().map(this::toFieldVO).collect(Collectors.toList()));
        return Result.success(vo);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "指标管理", operation = "创建指标", targetType = "Indicator")
    public Result<Void> save(@Valid @RequestBody IndicatorDTO dto) {
        indicatorService.saveIndicator(dto);
        return Result.success();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "指标管理", operation = "更新指标", targetType = "Indicator")
    public Result<Void> update(@Valid @RequestBody IndicatorDTO dto) {
        indicatorService.updateIndicator(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "指标管理", operation = "删除指标", targetType = "Indicator")
    public Result<Void> delete(@PathVariable Long id) {
        indicatorService.deleteIndicator(id);
        return Result.success();
    }

    private IndicatorVO toVO(Indicator entity) {
        IndicatorVO vo = new IndicatorVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private IndicatorFieldVO toFieldVO(IndicatorField entity) {
        IndicatorFieldVO vo = new IndicatorFieldVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
