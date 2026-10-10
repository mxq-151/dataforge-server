package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标注缺口接口的「按环境过滤」SQL 片段。
 *
 * <p>这里只钉住两件事，都是「写错了也能编译、但结果会悄悄错」的地方：</p>
 * <ul>
 *   <li>不传环境时不追加任何条件（保持原有全量行为）；</li>
 *   <li>追加时必须把 {@code _} 转义掉 —— 否则 LIKE 的 {@code _} 会匹配任意单字符，
 *       {@code %_prod} 会把 {@code xaprod} 这类库名也算进「生产环境」。</li>
 * </ul>
 */
class MetadataAnnotationGapEnvSqlTest {

    private static String sqlOf(String env) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        MetadataAnnotationGapService.applyEnvFilter(wrapper, env);
        return wrapper.getCustomSqlSegment();
    }

    @Test
    @DisplayName("不传环境：不追加任何 where 条件")
    void noEnvAppendsNothing() {
        assertFalse(sqlOf(null).contains("LIKE"), sqlOf(null));
    }

    @Test
    @DisplayName("指定环境：按库名后缀 LIKE，且用 ! 转义下划线")
    void envAppendsEscapedSuffixLike() {
        String sql = sqlOf("prod");
        assertTrue(sql.contains("database_name LIKE"), sql);
        assertTrue(sql.contains("ESCAPE '!'"), "转义字符必须显式声明，否则 _ 会被当作通配符：" + sql);
        // 参数以占位符形式绑定，环境值不进 SQL 文本（避免注入）
        assertFalse(sql.contains("prod"), "环境值不能拼进 SQL 文本：" + sql);
    }

    @Test
    @DisplayName("环境值不同只影响绑定参数，SQL 片段结构一致")
    void structureIsStableAcrossEnvs() {
        assertEquals(sqlOf("prod"), sqlOf("gray"));
        assertEquals(sqlOf("prod"), sqlOf("test"));
    }
}
