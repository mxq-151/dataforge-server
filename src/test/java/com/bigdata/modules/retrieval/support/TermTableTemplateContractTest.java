package com.bigdata.modules.retrieval.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器模板 ↔ 服务端解析器的契约测试。
 *
 * <p>知识库编辑器里「插入术语表模板」写入的文本，必须能被 {@link TermDictionary} 解析。
 * 这两端一个是 TypeScript 常量、一个是 Java 解析器，没有任何编译期约束把它们绑在一起 ——
 * 一旦模板改了（比如把表头从「术语」改成「标准名称」），作者会得到一段「看起来像术语表、
 * 实际解析不到」的死文本，而且没有任何报错。因此这里用<b>逐字相同的模板文本</b>做断言。</p>
 *
 * <p>模板正文与 {@code src/views/knowledge-base/termTableTemplate.ts} 的
 * {@code TERM_TABLE_TEMPLATE} 保持一致；改任一侧都必须同步另一侧。</p>
 */
class TermTableTemplateContractTest {

    /** 与前端 termTableTemplate.ts 的 TERM_TABLE_TEMPLATE 逐字一致 */
    private static final String TEMPLATE = """
            ## 术语表

            | 术语 | 别名 | 英文名 |
            | --- | --- | --- |
            | 订单明细 | 订单流水、下单记录 | order_detail |
            """;

    @Test
    @DisplayName("编辑器插入的模板能被解析成术语条目")
    void templateIsParseable() {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, "业务知识", TEMPLATE, "knowledge:1:订单域说明");

        assertEquals(1, dictionary.size(), "模板应恰好解析出一条术语");

        TermDictionary.Entry entry = dictionary.lookup("订单流水");
        assertNotNull(entry, "别名「订单流水」应能反查到标准说法");
        assertEquals("订单明细", entry.term());
        assertTrue(entry.mappings().contains("order_detail"), "英文名应被解析为命名映射");
    }

    @Test
    @DisplayName("模板扩展出英文命名，支撑「业务词 → 物理表名」召回")
    void templateEnablesRecallExpansion() {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, "业务知识", TEMPLATE, "knowledge:1:订单域说明");

        List<String> expansions = TermDictionary.expand(dictionary, List.of("订单流水"));

        assertTrue(expansions.contains("order_detail"), "扩展词应包含英文命名，实际=" + expansions);
        assertTrue(expansions.contains("订单明细"));
    }

    @Test
    @DisplayName("模板本身不触发重复定义告警")
    void templateHasNoSelfConflict() {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, "业务知识", TEMPLATE, "knowledge:1:订单域说明");

        assertTrue(dictionary.conflicts().isEmpty());
    }
}
