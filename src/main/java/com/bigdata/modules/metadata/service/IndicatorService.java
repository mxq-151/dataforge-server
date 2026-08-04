package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.metadata.dto.IndicatorDTO;
import com.bigdata.modules.metadata.dto.IndicatorFieldDTO;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.IndicatorField;
import com.bigdata.modules.metadata.mapper.IndicatorFieldMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
public class IndicatorService {

    @Autowired
    private IndicatorMapper indicatorMapper;

    @Autowired
    private IndicatorFieldMapper indicatorFieldMapper;

    public List<Indicator> getIndicatorsByFilter(String projectCode, String topic, String keyword) {
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        if (projectCode != null && !projectCode.isEmpty()) {
            wrapper.eq(Indicator::getProjectCode, projectCode);
        }
        if (topic != null && !topic.isEmpty()) {
            wrapper.eq(Indicator::getTopic, topic);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Indicator::getName, keyword)
                    .or().like(Indicator::getCode, keyword)
                    .or().like(Indicator::getBusinessDefinition, keyword));
        }
        wrapper.orderByDesc(Indicator::getCreatedAt);
        return indicatorMapper.selectList(wrapper);
    }

    public List<Indicator> getIndicatorsByFilter(Collection<String> projectCodes, String keyword) {
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        if (projectCodes != null && !projectCodes.isEmpty()) {
            wrapper.in(Indicator::getProjectCode, projectCodes);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Indicator::getName, keyword)
                    .or().like(Indicator::getCode, keyword)
                    .or().like(Indicator::getBusinessDefinition, keyword));
        }
        wrapper.orderByDesc(Indicator::getCreatedAt);
        return indicatorMapper.selectList(wrapper);
    }

    public Page<Indicator> getIndicatorList(int current, int size, String projectCode, String topic, String name, String keyword) {
        Page<Indicator> page = new Page<>(current, size);
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        if (projectCode != null && !projectCode.isEmpty()) {
            wrapper.eq(Indicator::getProjectCode, projectCode);
        }
        if (topic != null && !topic.isEmpty()) {
            wrapper.eq(Indicator::getTopic, topic);
        }
        if (name != null && !name.isEmpty()) {
            wrapper.like(Indicator::getName, name);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Indicator::getName, keyword)
                    .or().like(Indicator::getCode, keyword)
                    .or().like(Indicator::getBusinessDefinition, keyword));
        }
        wrapper.orderByDesc(Indicator::getCreatedAt);
        return indicatorMapper.selectPage(page, wrapper);
    }

    public Indicator getIndicatorById(Long id) {
        return indicatorMapper.selectById(id);
    }

    public List<IndicatorField> getFieldsByIndicatorId(Long indicatorsId) {
        LambdaQueryWrapper<IndicatorField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IndicatorField::getIndicatorsId, indicatorsId);
        return indicatorFieldMapper.selectList(wrapper);
    }

    @Transactional
    public void saveIndicator(IndicatorDTO dto) {
        Indicator indicator = new Indicator();
        indicator.setProjectCode(dto.getProjectCode());
        indicator.setTopic(dto.getTopic());
        indicator.setName(dto.getName());
        indicator.setCode(dto.getCode());
        indicator.setBusinessDefinition(dto.getBusinessDefinition());
        indicator.setCalcExpr(dto.getCalcExpr());
        indicator.setDefaultFilter(dto.getDefaultFilter());
        indicator.setAlertConfig(dto.getAlertConfig());
        indicator.setBusinessOwner(dto.getBusinessOwner());
        indicator.setTechOwner(dto.getTechOwner());
        indicator.setRefreshInterval(dto.getRefreshInterval());
        indicator.setRefreshUnit(dto.getRefreshUnit());
        indicator.setRefreshDesc(dto.getRefreshDesc());
        indicator.setVisibility(dto.getVisibility());

        indicatorMapper.upsertByCode(indicator);

        // upsert后根据code查出id，用于保存关联字段
        LambdaQueryWrapper<Indicator> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Indicator::getCode, dto.getCode());
        Indicator saved = indicatorMapper.selectOne(queryWrapper);

        // 删除旧字段，重新插入
        LambdaQueryWrapper<IndicatorField> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(IndicatorField::getIndicatorsId, saved.getId());
        indicatorFieldMapper.delete(deleteWrapper);

        saveFields(saved.getId(), dto.getFields());
    }

    @Transactional
    public void updateIndicator(IndicatorDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("指标ID不能为空");
        }
        Indicator existing = indicatorMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException("指标不存在");
        }

        existing.setProjectCode(dto.getProjectCode());
        existing.setTopic(dto.getTopic());
        existing.setName(dto.getName());
        existing.setCode(dto.getCode());
        existing.setBusinessDefinition(dto.getBusinessDefinition());
        existing.setCalcExpr(dto.getCalcExpr());
        existing.setDefaultFilter(dto.getDefaultFilter());
        existing.setAlertConfig(dto.getAlertConfig());
        existing.setBusinessOwner(dto.getBusinessOwner());
        existing.setTechOwner(dto.getTechOwner());
        existing.setRefreshInterval(dto.getRefreshInterval());
        existing.setRefreshUnit(dto.getRefreshUnit());
        existing.setRefreshDesc(dto.getRefreshDesc());
        existing.setVisibility(dto.getVisibility());
        indicatorMapper.updateById(existing);

        // 删除旧字段，重新插入
        LambdaQueryWrapper<IndicatorField> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(IndicatorField::getIndicatorsId, dto.getId());
        indicatorFieldMapper.delete(deleteWrapper);

        saveFields(dto.getId(), dto.getFields());
    }

    @Transactional
    public void deleteIndicator(Long id) {
        Indicator existing = indicatorMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("指标不存在");
        }
        indicatorMapper.deleteById(id);

        LambdaQueryWrapper<IndicatorField> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(IndicatorField::getIndicatorsId, id);
        indicatorFieldMapper.delete(deleteWrapper);
    }

    @Transactional
    public void deleteIndicatorByCode(String code) {
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Indicator::getCode, code);
        Indicator existing = indicatorMapper.selectOne(wrapper);
        if (existing == null) {
            throw new BusinessException("指标不存在: " + code);
        }
        indicatorMapper.deleteById(existing.getId());

        LambdaQueryWrapper<IndicatorField> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(IndicatorField::getIndicatorsId, existing.getId());
        indicatorFieldMapper.delete(deleteWrapper);
    }

    @Transactional
    public void batchSaveIndicators(List<IndicatorDTO> dtoList) {
        for (IndicatorDTO dto : dtoList) {
            saveIndicator(dto);
        }
    }

    @Transactional
    public void batchDeleteIndicators(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            deleteIndicator(id);
        }
    }

    private void saveFields(Long indicatorsId, List<IndicatorFieldDTO> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        for (IndicatorFieldDTO fieldDTO : fields) {
            IndicatorField field = new IndicatorField();
            field.setIndicatorsId(indicatorsId);
            field.setDatasourceName(fieldDTO.getDatasourceName());
            field.setDbName(fieldDTO.getDbName());
            field.setTblName(fieldDTO.getTblName());
            field.setFieldName(fieldDTO.getFieldName());
            field.setFieldType(fieldDTO.getFieldType());
            indicatorFieldMapper.insert(field);
        }
    }
}
