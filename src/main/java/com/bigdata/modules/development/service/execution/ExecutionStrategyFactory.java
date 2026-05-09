package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
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

    private final Map<EngineType, ExecutionStrategy> strategyMap;

    @Autowired
    public ExecutionStrategyFactory(List<ExecutionStrategy> strategies) {
        this.strategyMap = new HashMap<>();
        for (ExecutionStrategy strategy : strategies) {
            strategyMap.put(strategy.getEngineType(), strategy);
            log.info("注册执行策略: {} -> {}", strategy.getEngineType(), strategy.getClass().getSimpleName());
        }
    }

    public ExecutionStrategy getStrategy(EngineType engineType) {
        ExecutionStrategy strategy = strategyMap.get(engineType);
        if (strategy == null) {
            throw new BusinessException("不支持的执行引擎: " + engineType);
        }
        return strategy;
    }

    public ExecutionStrategy getStrategy(EngineType engineType, CodeType codeType) {
        ExecutionStrategy strategy = getStrategy(engineType);
        if (!strategy.supports(codeType)) {
            throw new BusinessException(
                    String.format("执行引擎 %s 不支持代码类型 %s，支持的类型: %s",
                            engineType.getDescription(),
                            codeType.getDescription(),
                            engineType.getSupportedCodeTypes()));
        }
        return strategy;
    }

    public boolean isSupported(EngineType engineType, CodeType codeType) {
        ExecutionStrategy strategy = strategyMap.get(engineType);
        return strategy != null && strategy.supports(codeType);
    }

    public List<EngineType> getAvailableEngines() {
        return strategyMap.keySet().stream().toList();
    }
}
