package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.entity.DataQualityCheckResult;
import com.bigdata.modules.quality.mapper.DataQualityCheckResultMapper;
import com.bigdata.modules.quality.vo.DataQualityFailStatisticVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 数据质量校验结果 Service
 */
@Service
public class DataQualityCheckResultService {

    @Autowired
    private DataQualityCheckResultMapper dataQualityCheckResultMapper;

    /**
     * 分页查询校验结果
     *
     * @param current       页码
     * @param size          每页条数
     * @param datasourceId  数据源ID(可选)
     * @param databaseName  数据库名(可选)
     * @param tableName     表名(可选)
     * @param checkItem     检查项(可选，按检查项名称模糊匹配，兼容历史自由文本数据)
     * @param ruleType      规则类型(可选，7 类之一的编码；新链路写入的数据才有值)
     * @param failOnly      是否只看未通过记录
     */
    public Page<DataQualityCheckResult> getResultPage(int current, int size, Long datasourceId,
                                                      String databaseName, String tableName,
                                                      String checkItem, String ruleType, Boolean failOnly) {
        Page<DataQualityCheckResult> page = new Page<>(current, size);
        LambdaQueryWrapper<DataQualityCheckResult> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(DataQualityCheckResult::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isEmpty()) {
            wrapper.eq(DataQualityCheckResult::getDatabaseName, databaseName);
        }
        if (tableName != null && !tableName.isEmpty()) {
            wrapper.like(DataQualityCheckResult::getTableName, tableName);
        }
        if (checkItem != null && !checkItem.isEmpty()) {
            wrapper.like(DataQualityCheckResult::getCheckItem, checkItem);
        }
        if (ruleType != null && !ruleType.isEmpty()) {
            // 精确匹配编码而不是模糊：规则类型是枚举，模糊匹配会把
            // ROW_COUNT_FLUCTUATION 与 ROW_COUNT_MOM 混在一起
            wrapper.eq(DataQualityCheckResult::getRuleType, ruleType.trim().toUpperCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(failOnly)) {
            wrapper.gt(DataQualityCheckResult::getFailRecordCount, 0);
        }
        wrapper.orderByDesc(DataQualityCheckResult::getCheckTime)
                .orderByDesc(DataQualityCheckResult::getId);
        return dataQualityCheckResultMapper.selectPage(page, wrapper);
    }

    public DataQualityCheckResult getResultById(Long id) {
        DataQualityCheckResult result = dataQualityCheckResultMapper.selectById(id);
        if (result == null) {
            throw new BusinessException("校验结果不存在");
        }
        return result;
    }

    public void saveResult(DataQualityCheckResult result) {
        dataQualityCheckResultMapper.insert(result);
    }

    public void updateResult(DataQualityCheckResult result) {
        DataQualityCheckResult existing = dataQualityCheckResultMapper.selectById(result.getId());
        if (existing == null) {
            throw new BusinessException("校验结果不存在");
        }
        dataQualityCheckResultMapper.updateById(result);
    }

    public void deleteResult(Long id) {
        DataQualityCheckResult result = dataQualityCheckResultMapper.selectById(id);
        if (result == null) {
            throw new BusinessException("校验结果不存在");
        }
        dataQualityCheckResultMapper.deleteById(id);
    }

    /**
     * 检查失败记录统计：按 表 + 日期 + 检查项 聚合未通过记录数。
     *
     * @param startDate     统计起始日期(含,可选)
     * @param endDate       统计结束日期(含,可选)
     * @param datasourceId  数据源ID(可选)
     * @param databaseName  数据库名(可选)
     * @param tableName     表名模糊匹配(可选)
     */
    public List<DataQualityFailStatisticVO> getFailStatistic(LocalDate startDate, LocalDate endDate,
                                                             Long datasourceId, String databaseName,
                                                             String tableName) {
        return dataQualityCheckResultMapper.selectFailStatistic(
                startDate == null ? null : startDate.atStartOfDay(),
                endDate == null ? null : endDate.plusDays(1).atStartOfDay(),
                datasourceId, databaseName, tableName);
    }
}
