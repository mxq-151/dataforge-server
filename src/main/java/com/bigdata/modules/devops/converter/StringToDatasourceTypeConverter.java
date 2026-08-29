package com.bigdata.modules.devops.converter;

import com.bigdata.modules.devops.enums.DatasourceType;
import org.springframework.core.convert.converter.Converter;

/**
 * 将请求参数/路径变量中的 code 字符串转换为 {@link DatasourceType} 枚举。
 * <p>
 * Spring 默认按枚举常量名(如 {@code POSTGRESQL})绑定,无法识别前端传递的小写 code(如 {@code postgresql}),
 * 故注册该转换器,统一通过 {@link DatasourceType#fromCode(String)} 做大小写不敏感的匹配。
 * </p>
 * <p>
 * 对无法识别的取值必须抛出 {@link IllegalArgumentException}：合并 {@code StorageEngineType} 之前，
 * 该参数由 Spring 默认的 {@code Enum.valueOf} 绑定，非法取值会直接失败；而
 * {@link DatasourceType#fromCode(String)} 遇到未知取值返回 {@code null}，
 * 若原样透传会给下游造成"该参数没传"的假象。例如 {@code GET /metadata/table/columns} 会把
 * 非法的 {@code storageEngine} 当成 null，退化成"只按库+表查询"，从而把其它引擎下同名的列一起返回。
 * </p>
 */
public class StringToDatasourceTypeConverter implements Converter<String, DatasourceType> {

    @Override
    public DatasourceType convert(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        String trimmed = source.trim();
        DatasourceType type = DatasourceType.fromCode(trimmed);
        if (type == null) {
            throw new IllegalArgumentException(
                    "无法识别的数据源类型: " + trimmed + "，可选值: " + DatasourceType.allTypes());
        }
        return type;
    }
}