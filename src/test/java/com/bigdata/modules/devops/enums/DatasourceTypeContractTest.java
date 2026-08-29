package com.bigdata.modules.devops.enums;

import com.bigdata.modules.development.service.execution.CodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合并 {@code StorageEngineType} / {@code EngineType} 到 {@link DatasourceType} 后的枚举契约回归测试。
 * <p>
 * 合并动作同时改变了三处对外可见的契约，这里逐条锁定：
 * <ol>
 *   <li>类型全集：由原 DatasourceType 的 8 个扩展为 13 个（并入 spark / flink / hbase / hudi / iceberg）；</li>
 *   <li>描述文案：kyuubi / doris 由 "Kyuubi"、"Doris" 改为 "Apache Kyuubi"、"Apache Doris"；</li>
 *   <li>能力标记：新增 exec / store / language 三个属性，原 EngineType#getSupportedCodeTypes() 的能力迁移到 language。</li>
 * </ol>
 * </p>
 */
@DisplayName("DatasourceType 枚举契约回归（合并 StorageEngineType / EngineType 后）")
class DatasourceTypeContractTest {

    /** 合并前 DatasourceType 的 8 个类型，合并后必须仍然存在，否则老数据源记录无法解析 */
    private static final Set<String> LEGACY_DATASOURCE_TYPE_CODES =
            Set.of("mysql", "postgresql", "kyuubi", "doris", "clickhouse", "starrocks", "es", "hive");

    /** 合并前 StorageEngineType 的 10 个枚举名，前端历史上用这些大写名作为 storageEngine 入参 */
    private static final List<String> LEGACY_STORAGE_ENGINE_NAMES =
            List.of("HIVE", "DORIS", "STARROCKS", "HBASE", "MYSQL", "POSTGRESQL", "ES", "CLICKHOUSE", "HUDI", "ICEBERG");

    /** 合并前 EngineType 的 7 个 code，前端历史上用这些小写 code 作为 engineType 入参 */
    private static final List<String> LEGACY_ENGINE_TYPE_CODES =
            List.of("spark", "kyuubi", "flink", "doris", "hbase", "mysql", "postgresql");

    @Nested
    @DisplayName("类型全集")
    class TypeSetTests {

        @Test
        @DisplayName("合并后共 13 个类型，且 code 取值固定")
        void typeCountAndCodes() {
            List<String> codes = Arrays.stream(DatasourceType.values())
                    .map(DatasourceType::getCode)
                    .collect(Collectors.toList());

            assertEquals(13, codes.size(), "合并后应为 13 个数据源类型");
            assertEquals(List.of("spark", "flink", "kyuubi", "doris", "hbase", "mysql",
                    "postgresql", "clickhouse", "hive", "starrocks", "es", "hudi", "iceberg"), codes,
                    "枚举顺序与 code 取值发生变化会直接影响 /devops/datasource/types 的返回顺序");
        }

        @Test
        @DisplayName("合并前已存在的 8 个类型不能被移除")
        void legacyDatasourceTypesMustSurvive() {
            Set<String> codes = Arrays.stream(DatasourceType.values())
                    .map(DatasourceType::getCode)
                    .collect(Collectors.toSet());

            assertTrue(codes.containsAll(LEGACY_DATASOURCE_TYPE_CODES),
                    "以下老类型在合并后丢失: " + LEGACY_DATASOURCE_TYPE_CODES.stream()
                            .filter(c -> !codes.contains(c)).collect(Collectors.toList()));
        }

        @Test
        @DisplayName("原 StorageEngineType 的类型全部并入")
        void legacyStorageEngineTypesMerged() {
            Set<String> codes = Arrays.stream(DatasourceType.values())
                    .map(DatasourceType::getCode)
                    .collect(Collectors.toSet());

            List<String> missing = LEGACY_STORAGE_ENGINE_NAMES.stream()
                    .map(String::toLowerCase)
                    .filter(c -> !codes.contains(c))
                    .collect(Collectors.toList());

            assertTrue(missing.isEmpty(), "以下原 StorageEngineType 类型未并入: " + missing);
        }

        @Test
        @DisplayName("原 EngineType 的类型全部并入")
        void legacyEngineTypesMerged() {
            Set<String> codes = Arrays.stream(DatasourceType.values())
                    .map(DatasourceType::getCode)
                    .collect(Collectors.toSet());

            List<String> missing = LEGACY_ENGINE_TYPE_CODES.stream()
                    .filter(c -> !codes.contains(c))
                    .collect(Collectors.toList());

            assertTrue(missing.isEmpty(), "以下原 EngineType 类型未并入: " + missing);
        }
    }

    @Nested
    @DisplayName("描述文案")
    class DescriptionTests {

        @Test
        @DisplayName("kyuubi / doris 描述由短名改为 Apache 前缀长名")
        void renamedDescriptions() {
            assertEquals("Apache Kyuubi", DatasourceType.KYUUBI.getDescription(),
                    "原 DatasourceType 中 kyuubi 的描述为 'Kyuubi'，合并后改为 'Apache Kyuubi'");
            assertEquals("Apache Doris", DatasourceType.DORIS.getDescription(),
                    "原 DatasourceType 中 doris 的描述为 'Doris'，合并后改为 'Apache Doris'");
        }

        @Test
        @DisplayName("未受合并影响的类型描述保持原值")
        void untouchedDescriptions() {
            assertEquals("MySQL", DatasourceType.MYSQL.getDescription());
            assertEquals("PostgreSQL", DatasourceType.POSTGRESQL.getDescription());
            assertEquals("ClickHouse", DatasourceType.CLICKHOUSE.getDescription());
            assertEquals("Hive", DatasourceType.HIVE.getDescription());
            assertEquals("StarRocks", DatasourceType.STARROCKS.getDescription());
            assertEquals("Elasticsearch", DatasourceType.ES.getDescription());
        }

        @Test
        @DisplayName("每个类型的描述都不能为空")
        void descriptionsNotEmpty() {
            for (DatasourceType type : DatasourceType.values()) {
                assertTrue(type.getDescription() != null && !type.getDescription().isBlank(),
                        type.getCode() + " 的描述为空");
            }
        }
    }

    @Nested
    @DisplayName("exec / store 能力标记")
    class CapabilityTests {

        @Test
        @DisplayName("纯计算型引擎：spark / flink / kyuubi 不具备存储能力")
        void execOnlyTypes() {
            assertTrue(DatasourceType.SPARK.isExec() && !DatasourceType.SPARK.isStore());
            assertTrue(DatasourceType.FLINK.isExec() && !DatasourceType.FLINK.isStore());
            assertTrue(DatasourceType.KYUUBI.isExec() && !DatasourceType.KYUUBI.isStore());
        }

        @Test
        @DisplayName("纯存储型：hive / hudi / iceberg / es 不具备计算能力")
        void storeOnlyTypes() {
            for (DatasourceType type : List.of(DatasourceType.HIVE, DatasourceType.HUDI,
                    DatasourceType.ICEBERG, DatasourceType.ES)) {
                assertFalse(type.isExec(), type.getCode() + " 不应具备计算能力");
                assertTrue(type.isStore(), type.getCode() + " 应具备存储能力");
            }
        }

        @Test
        @DisplayName("计算+存储双能型：doris / hbase / mysql / postgresql / clickhouse / starrocks")
        void bothCapabilityTypes() {
            for (DatasourceType type : List.of(DatasourceType.DORIS, DatasourceType.HBASE,
                    DatasourceType.MYSQL, DatasourceType.POSTGRESQL, DatasourceType.CLICKHOUSE,
                    DatasourceType.STARROCKS)) {
                assertTrue(type.isExec(), type.getCode() + " 应具备计算能力");
                assertTrue(type.isStore(), type.getCode() + " 应具备存储能力");
            }
        }

        @Test
        @DisplayName("execTypes / storeTypes 与标记保持一致")
        void derivedCollections() {
            assertEquals(9, DatasourceType.execTypes().size(), "exec=true 的类型数量变化");
            assertEquals(10, DatasourceType.storeTypes().size(), "store=true 的类型数量变化");
            assertTrue(DatasourceType.execTypes().contains(DatasourceType.SPARK));
            assertFalse(DatasourceType.execTypes().contains(DatasourceType.HIVE));
            assertTrue(DatasourceType.storeTypes().contains(DatasourceType.HIVE));
            assertFalse(DatasourceType.storeTypes().contains(DatasourceType.SPARK));
        }

        @Test
        @DisplayName("每个类型至少具备一种能力")
        void everyTypeHasCapability() {
            for (DatasourceType type : DatasourceType.values()) {
                assertTrue(type.isExec() || type.isStore(),
                        type.getCode() + " 既不能计算也不能存储");
            }
        }
    }

    @Nested
    @DisplayName("language 支持的语言")
    class LanguageTests {

        @Test
        @DisplayName("spark / flink 支持 java、scala、python、sql")
        void sparkAndFlinkLanguages() {
            assertEquals(List.of(CodeType.JAVA, CodeType.SCALA, CodeType.PYTHON, CodeType.SQL),
                    DatasourceType.SPARK.getLanguage());
            assertEquals(List.of(CodeType.JAVA, CodeType.SCALA, CodeType.PYTHON, CodeType.SQL),
                    DatasourceType.FLINK.getLanguage());
        }

        @Test
        @DisplayName("其余类型仅支持 sql，es 不支持任何语言")
        void sqlOnlyLanguages() {
            for (DatasourceType type : DatasourceType.values()) {
                if (type == DatasourceType.SPARK || type == DatasourceType.FLINK || type == DatasourceType.ES) {
                    continue;
                }
                assertEquals(List.of(CodeType.SQL), type.getLanguage(),
                        type.getCode() + " 应仅支持 sql");
            }
            assertTrue(DatasourceType.ES.getLanguage().isEmpty(), "es 不应声明任何语言");
        }

        @Test
        @DisplayName("getSupportedCodeTypes 与 getLanguage 语义一致（兼容原 EngineType）")
        void aliasKeepsSameSemantics() {
            for (DatasourceType type : DatasourceType.values()) {
                assertEquals(type.getLanguage(), type.getSupportedCodeTypes(),
                        type.getCode() + " 的 getSupportedCodeTypes 应与 getLanguage 等价");
            }
        }

        @Test
        @DisplayName("supportsLanguage 与 language 列表一致")
        void supportsLanguage() {
            assertTrue(DatasourceType.SPARK.supportsLanguage(CodeType.SCALA));
            assertFalse(DatasourceType.DORIS.supportsLanguage(CodeType.SCALA));
            assertTrue(DatasourceType.DORIS.supportsLanguage(CodeType.SQL));
            assertFalse(DatasourceType.ES.supportsLanguage(CodeType.SQL));
        }
    }

    @Nested
    @DisplayName("fromCode 解析兼容性")
    class FromCodeTests {

        @Test
        @DisplayName("原 StorageEngineType 的大写枚举名可被解析")
        void legacyStorageEngineNamesResolvable() {
            for (String name : LEGACY_STORAGE_ENGINE_NAMES) {
                DatasourceType type = DatasourceType.fromCode(name);
                assertTrue(type != null, "原 StorageEngineType 枚举名 " + name + " 已无法解析");
                assertEquals(name.toLowerCase(), type.getCode());
            }
        }

        @Test
        @DisplayName("原 EngineType 的小写 code 可被解析")
        void legacyEngineCodesResolvable() {
            for (String code : LEGACY_ENGINE_TYPE_CODES) {
                DatasourceType type = DatasourceType.fromCode(code);
                assertTrue(type != null, "原 EngineType code " + code + " 已无法解析");
                assertEquals(code, type.getCode());
            }
        }

        @Test
        @DisplayName("解析大小写不敏感")
        void caseInsensitive() {
            assertEquals(DatasourceType.POSTGRESQL, DatasourceType.fromCode("PostgreSQL"));
            assertEquals(DatasourceType.POSTGRESQL, DatasourceType.fromCode("postgresql"));
            assertEquals(DatasourceType.POSTGRESQL, DatasourceType.fromCode("POSTGRESQL"));
        }

        @Test
        @DisplayName("未知值返回 null 而不是抛异常")
        void unknownValueReturnsNull() {
            assertNull(DatasourceType.fromCode("not_a_type"));
            assertNull(DatasourceType.fromCode(""));
        }
    }

    @Nested
    @DisplayName("JSON / MyBatis 序列化取值")
    class SerializationValueTests {

        @Test
        @DisplayName("@JsonValue 落在 code 上，序列化输出小写 code")
        void jsonValueIsCode() throws Exception {
            java.lang.reflect.Field field = DatasourceType.class.getDeclaredField("code");
            assertTrue(field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonValue.class),
                    "DatasourceType 的 @JsonValue 必须落在 code 上，否则接口返回值会从 'mysql' 变回 'MYSQL'");
            assertTrue(field.isAnnotationPresent(com.baomidou.mybatisplus.annotation.EnumValue.class),
                    "DatasourceType 的 @EnumValue 必须落在 code 上，否则落库值会与历史数据不一致");
        }
    }
}
