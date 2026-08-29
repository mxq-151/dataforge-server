package com.bigdata.modules.devops.converter;

import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code storageEngine} / {@code datasourceType} 请求参数绑定契约回归。
 * <p>
 * 合并前 {@code @RequestParam StorageEngineType} 走 Spring 默认的 {@code StringToEnumConverterFactory}，
 * 即 {@code Enum.valueOf}，只接受与枚举名完全一致的取值（{@code HIVE}、{@code MYSQL}），且大小写严格。
 * 合并后 {@link StringToDatasourceTypeConverter} 接管绑定，改为按 code 做大小写不敏感匹配。
 * 本测试锁定两种历史入参写法在小写 code 化之后仍可正常绑定。
 * </p>
 */
@DisplayName("请求参数绑定回归 - StringToDatasourceTypeConverter")
class StringToDatasourceTypeConverterTest {

    private final StringToDatasourceTypeConverter converter = new StringToDatasourceTypeConverter();

    @ParameterizedTest(name = "小写 code \"{0}\" 应绑定为 {1}")
    @CsvSource({
            "spark,SPARK",
            "flink,FLINK",
            "kyuubi,KYUUBI",
            "doris,DORIS",
            "hbase,HBASE",
            "mysql,MYSQL",
            "postgresql,POSTGRESQL",
            "clickhouse,CLICKHOUSE",
            "hive,HIVE",
            "starrocks,STARROCKS",
            "es,ES",
            "hudi,HUDI",
            "iceberg,ICEBERG"
    })
    @DisplayName("全部 13 个类型的 code 均可绑定")
    void bindsAllCodes(String code, DatasourceType expected) {
        assertEquals(expected, converter.convert(code));
    }

    @ParameterizedTest(name = "原 StorageEngineType 大写枚举名 \"{0}\" 应仍可绑定")
    @ValueSource(strings = {"HIVE", "DORIS", "STARROCKS", "HBASE", "MYSQL", "POSTGRESQL",
            "ES", "CLICKHOUSE", "HUDI", "ICEBERG"})
    @DisplayName("改动前前端传的大写枚举名不能被绑定失败")
    void bindsLegacyStorageEngineNames(String legacyName) {
        DatasourceType type = converter.convert(legacyName);
        assertEquals(legacyName, type.name(),
                "历史入参 " + legacyName + " 绑定到了错误的类型: " + type);
    }

    @ParameterizedTest(name = "混合大小写 \"{0}\" 应绑定为 MYSQL")
    @ValueSource(strings = {"MySQL", "MySql", "MYsql", "  mysql  "})
    @DisplayName("大小写不敏感且容忍首尾空白")
    void bindsCaseInsensitivelyAndTrims(String raw) {
        assertEquals(DatasourceType.MYSQL, converter.convert(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("空值返回 null，交由 required 校验处理")
    void nullAndBlankReturnNull(String raw) {
        assertNull(converter.convert(raw));
    }

    @Test
    @DisplayName("未知类型必须抛异常，不能静默返回 null")
    void unknownTypeMustThrow() {
        // 合并前该参数由 Enum.valueOf 绑定，非法取值直接失败；
        // 若这里返回 null，/metadata/table/columns 会把非法 storageEngine 当成"未传"，
        // 退化成"只按库+表查询"，从而把其它引擎下同名的列一起返回。
        assertThrows(IllegalArgumentException.class, () -> converter.convert("not_a_type"));
        assertThrows(IllegalArgumentException.class, () -> converter.convert("MYSQL8"));
    }
}
