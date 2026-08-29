package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源类型到执行策略的路由回归。
 * <p>
 * 合并前 {@code ExecutionStrategyFactory#getStrategy(DatasourceConfig)} 依据
 * {@code config.getStorageEngine()}（{@code StorageEngineType}）路由，
 * 且 switch 的 default 分支抛 {@code 不支持的存储引擎类型}；
 * 合并后依据 {@code config.getDatasourceType()}（{@link DatasourceType}）路由，
 * default 分支改为「原样返回」。这里把两种数据源类型取值下的路由结果逐条锁定。
 * </p>
 */
@DisplayName("数据源类型 -> 执行策略 路由回归")
class ExecutionStrategyRoutingRegressionTest {

    private static ExecutionStrategy stub(DatasourceType engineType) {
        return new ExecutionStrategy() {
            @Override
            public CodeExecutionResult execute(CodeExecutionContext context) {
                return null;
            }

            @Override
            public DatasourceType getEngineType() {
                return engineType;
            }

            @Override
            public boolean supports(CodeType codeType) {
                return true;
            }
        };
    }

    private static ExecutionStrategyFactory factory() {
        return new ExecutionStrategyFactory(Arrays.stream(DatasourceType.values())
                .filter(DatasourceType::isExec)
                .map(ExecutionStrategyRoutingRegressionTest::stub)
                .map(s -> (ExecutionStrategy) s)
                .toList());
    }

    private static DatasourceConfig config(DatasourceType type) {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("ds_" + type.getCode());
        config.setDatasourceType(type);
        return config;
    }

    @ParameterizedTest(name = "datasourceType={0} 应路由到 {1} 策略")
    @CsvSource({
            "HIVE,      KYUUBI",
            "DORIS,     DORIS",
            "STARROCKS, DORIS",
            "MYSQL,     DORIS",
            "CLICKHOUSE,DORIS",
            "POSTGRESQL,DORIS",
            "HBASE,     HBASE",
            "ES,        DORIS",
            "HUDI,      KYUUBI",
            "ICEBERG,   KYUUBI"
    })
    @DisplayName("存储型数据源的路由映射保持不变")
    void routingOfStorageTypes(DatasourceType datasourceType, DatasourceType expectedEngine) {
        ExecutionStrategyFactory factory = factory();
        ExecutionStrategy strategy = factory.getStrategy(config(datasourceType));
        assertEquals(expectedEngine, strategy.getEngineType(),
                datasourceType.getCode() + " 的路由结果发生变化");
    }

    @ParameterizedTest(name = "datasourceType={0} 应路由到自身")
    @CsvSource({"SPARK", "FLINK", "KYUUBI"})
    @DisplayName("计算型数据源路由到自身（合并后 default 分支改为原样返回）")
    void routingOfExecTypes(DatasourceType datasourceType) {
        ExecutionStrategyFactory factory = factory();
        assertEquals(datasourceType, factory.getStrategy(config(datasourceType)).getEngineType());
    }

    @Test
    @DisplayName("spark / flink / kyuubi 合并前在 switch 中会抛异常，合并后可用")
    void execTypesNoLongerRejected() {
        ExecutionStrategyFactory factory = factory();
        for (DatasourceType type : List.of(DatasourceType.SPARK, DatasourceType.FLINK, DatasourceType.KYUUBI)) {
            assertEquals(type, factory.getStrategy(config(type)).getEngineType(),
                    type.getCode() + " 现在应能直接路由到对应策略");
        }
    }

    @Test
    @DisplayName("数据源类型为 null 时明确报错")
    void nullDatasourceTypeRejected() {
        ExecutionStrategyFactory factory = factory();
        DatasourceConfig config = new DatasourceConfig();

        BusinessException ex = assertThrows(BusinessException.class, () -> factory.getStrategy(config));
        assertTrue(ex.getMessage().contains("数据源类型"), "异常信息应指向 datasourceType: " + ex.getMessage());
    }

    @Test
    @DisplayName("未注册策略的类型（hive 本身）直接按引擎查询时报不支持")
    void unregisteredEngineRejected() {
        ExecutionStrategyFactory factory = factory();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.getStrategy(DatasourceType.HIVE));
        assertTrue(ex.getMessage().contains("不支持的执行引擎"), ex.getMessage());
    }

    @Test
    @DisplayName("getStrategy(engineType) 与 getStrategy(config) 对同一数据源返回同一实例")
    void sameInstanceForBothOverloads() {
        ExecutionStrategyFactory factory = factory();
        ExecutionStrategy byConfig = factory.getStrategy(config(DatasourceType.HIVE));
        ExecutionStrategy byEngine = factory.getStrategy(DatasourceType.KYUUBI);
        assertSame(byEngine, byConfig);
    }

    @Test
    @DisplayName("可用引擎集合为全部 exec 类型")
    void availableEngines() {
        List<DatasourceType> engines = factory().getAvailableEngines();
        assertEquals(9, engines.size(), "可用引擎数量应为 9（exec=true 的类型）");
        assertTrue(engines.containsAll(List.of(DatasourceType.SPARK, DatasourceType.FLINK,
                DatasourceType.KYUUBI, DatasourceType.DORIS, DatasourceType.HBASE,
                DatasourceType.MYSQL, DatasourceType.POSTGRESQL, DatasourceType.CLICKHOUSE,
                DatasourceType.STARROCKS)));
    }
}
