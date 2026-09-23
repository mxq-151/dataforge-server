package com.bigdata.modules.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.quality.entity.DataQualityCheckResult;
import com.bigdata.modules.quality.vo.DataQualityFailStatisticVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataQualityCheckResultMapper extends BaseMapper<DataQualityCheckResult> {

    /**
     * 检查失败记录统计：按 表 + 日期 + 检查项 聚合未通过记录数。
     * SQL 见 mapper/quality/DataQualityCheckResultMapper.xml
     */
    List<DataQualityFailStatisticVO> selectFailStatistic(@Param("startTime") LocalDateTime startTime,
                                                         @Param("endTime") LocalDateTime endTime,
                                                         @Param("datasourceId") Long datasourceId,
                                                         @Param("databaseName") String databaseName,
                                                         @Param("tableName") String tableName);
}
