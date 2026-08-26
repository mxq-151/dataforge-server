package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExecutionStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStrategyFactory.class);

    private final Map<DatasourceType, ExecutionStrategy> strategyMap;

    @Autowired
    public ExecutionStrategyFactory(List<ExecutionStrategy> strategies) {
        this.strategyMap = new HashMap<>();
        for (ExecutionStrategy strategy : strategies) {
            strategyMap.put(strategy.getEngineType(), strategy);
            log.info("注册执行策略: {} -> {}", strategy.getEngineType(), strategy.getClass().getSimpleName());
        }
    }

    public ExecutionStrategy getStrategy(DatasourceType engineType) {
        ExecutionStrategy strategy = strategyMap.get(engineType);
        if (strategy == null) {
            throw new BusinessException("不支持的执行引擎: " + engineType);
        }
        return strategy;
    }

    public ExecutionStrategy getStrategy(DatasourceType engineType, CodeType codeType) {
        ExecutionStrategy strategy = getStrategy(engineType);
        if (!strategy.supports(codeType)) {
            throw new BusinessException(
                    String.format("执行引擎 %s 不支持代码类型 %s，支持的类型: %s",
                            engineType.getDescription(),
                            codeType.getDescription(),
                            engineType.getLanguage()));
        }
        return strategy;
    }

    public boolean isSupported(DatasourceType engineType, CodeType codeType) {
        ExecutionStrategy strategy = strategyMap.get(engineType);
        return strategy != null && strategy.supports(codeType);
    }

    public List<DatasourceType> getAvailableEngines() {
        return strategyMap.keySet().stream().toList();
    }

    /**
     * 根据数据源配置获取对应的执行策略。
     * <p>
     * 数据源仅持有统一的 {@link DatasourceType} 类型，这里复用原先
     * "存储引擎 -> 计算引擎" 的解析关系，将存储型类型映射到其对应的执行策略。
     */
    public ExecutionStrategy getStrategy(DatasourceConfig datasourceConfig) {
        if (datasourceConfig == null || datasourceConfig.getDatasourceType() == null) {
            throw new BusinessException("数据源配置或数据源类型不能为空");
        }
        DatasourceType engineType = resolveStrategyType(datasourceConfig.getDatasourceType());
        return getStrategy(engineType);
    }

    private static DatasourceType resolveStrategyType(DatasourceType datasourceType) {
        switch (datasourceType) {
            case HIVE:
                return DatasourceType.KYUUBI;
            case DORIS:
            case STARROCKS:
            case MYSQL:
            case CLICKHOUSE:
            case POSTGRESQL:
                return DatasourceType.DORIS;
            case HBASE:
                return DatasourceType.HBASE;
            case ES:
                // ES暂无对应策略，使用Doris JDBC策略
                return DatasourceType.DORIS;
            case HUDI:
            case ICEBERG:
                // 数据湖格式通过Kyuubi(Hive Metastore)查询
                return DatasourceType.KYUUBI;
            default:
                return datasourceType;
        }
    }
}
