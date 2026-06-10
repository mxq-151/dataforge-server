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

import java.util.List;

@Service
public class IndicatorService {

    @Autowired
    private IndicatorMapper indicatorMapper;

    @Autowired
    private IndicatorFieldMapper indicatorFieldMapper;

    public List<Indicator> getIndicatorsByFilter(String projectName, String topic, String keyword) {
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        if (projectName != null && !projectName.isEmpty()) {
            wrapper.eq(Indicator::getProjectName, projectName);
        }
        if (topic != null && !topic.isEmpty()) {
            wrapper.eq(Indicator::getTopic, topic);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Indicator::getName, keyword)
                    .or().like(Indicator::getAlias, keyword)
                    .or().like(Indicator::getBusinessDefinition, keyword));
        }
        wrapper.orderByDesc(Indicator::getCreatedAt);
        return indicatorMapper.selectList(wrapper);
    }

    public Page<Indicator> getIndicatorList(int current, int size, String projectName, String topic, String name, String keyword) {
        Page<Indicator> page = new Page<>(current, size);
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        if (projectName != null && !projectName.isEmpty()) {
            wrapper.eq(Indicator::getProjectName, projectName);
        }
        if (topic != null && !topic.isEmpty()) {
            wrapper.eq(Indicator::getTopic, topic);
        }
        if (name != null && !name.isEmpty()) {
            wrapper.like(Indicator::getName, name);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Indicator::getName, keyword)
                    .or().like(Indicator::getAlias, keyword)
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
        indicator.setProjectName(dto.getProjectName());
        indicator.setTopic(dto.getTopic());
        indicator.setName(dto.getName());
        indicator.setAlias(dto.getAlias());
        indicator.setBusinessDefinition(dto.getBusinessDefinition());
        indicator.setDefaultFilter(dto.getDefaultFilter());
        indicator.setAlertConfig(dto.getAlertConfig());
        indicator.setBusinessOwner(dto.getBusinessOwner());
        indicator.setTechOwner(dto.getTechOwner());
        indicator.setRefreshInterval(dto.getRefreshInterval());
        indicator.setRefreshUnit(dto.getRefreshUnit());
        indicator.setRefreshDesc(dto.getRefreshDesc());
        indicator.setVisibility(dto.getVisibility());
        indicatorMapper.insert(indicator);

        saveFields(indicator.getId(), dto.getFields());
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

        existing.setProjectName(dto.getProjectName());
        existing.setTopic(dto.getTopic());
        existing.setName(dto.getName());
        existing.setAlias(dto.getAlias());
        existing.setBusinessDefinition(dto.getBusinessDefinition());
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
