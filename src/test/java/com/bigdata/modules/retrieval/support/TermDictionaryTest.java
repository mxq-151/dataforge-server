package com.bigdata.modules.retrieval.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 术语词典解析与扩展的口径测试。
 *
 * <p>词典是「召回增强」而不是「召回前提」：解析失败必须退化成空词典，
 * 扩展必须排除原词且有上限 —— 否则一个泛化别名会把检索放大成全表扫描。</p>
 */
class TermDictionaryTest {

    private static TermDictionary.Dictionary parse(String title, String content) {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, title, content, "knowledge:1");
        return dictionary;
    }

    @Test
    @DisplayName("表格写法：术语 / 别名 / 英文名三列都能被解析")
    void parsesMarkdownTable() {
        TermDictionary.Dictionary dictionary = parse("业务知识", """
                # 交易域说明

                ## 术语表

                | 术语 | 别名 | 英文名 |
                | --- | --- | --- |
                | 订单明细 | 订单流水、下单记录 | order_detail |
                | 成交额 | GMV、交易额 | gmv |
                """);

        assertEquals(2, dictionary.size());
        TermDictionary.Entry entry = dictionary.lookup("订单流水");
        assertNotNull(entry);
        assertEquals("订单明细", entry.term());
        assertTrue(entry.mappings().contains("order_detail"));

        // 英文写法也应能反查回中文标准说法
        assertEquals("成交额", dictionary.lookup("gmv").term());
    }

    @Test
    @DisplayName("列表写法：- 术语：别名、英文名")
    void parsesBulletList() {
        TermDictionary.Dictionary dictionary = parse("业务知识", """
                ## 术语表

                - 订单明细：订单流水、order_detail
                - 退款：退货、refund
                """);

        assertEquals(2, dictionary.size());
        assertEquals("订单明细", dictionary.lookup("order_detail").term());
        assertTrue(dictionary.lookup("退货").aliases().contains("退货"));
    }

    @Test
    @DisplayName("不在「术语表」章节里的内容不会被误当术语")
    void ignoresOtherSections() {
        TermDictionary.Dictionary dictionary = parse("业务知识", """
                ## 口径

                订单明细 = 订单主表 + 订单项，order_detail。
                """);

        assertTrue(dictionary.isEmpty());
    }

    @Test
    @DisplayName("标题含「术语」时，整篇按术语表解析（不要求有「术语表」章节标题）")
    void wholeDocumentMode() {
        TermDictionary.Dictionary dictionary = parse("业务术语表", """
                | 术语 | 别名 | 英文名 |
                | --- | --- | --- |
                | 订单明细 | 订单流水 | order_detail |
                """);

        assertEquals(1, dictionary.size());
        assertEquals("订单明细", dictionary.lookup("order_detail").term());
    }

    @Test
    @DisplayName("扩展：包含标准说法、别名与英文名，且排除原词")
    void expandsAliasesAndMappings() {
        TermDictionary.Dictionary dictionary = parse("业务知识", """
                ## 术语表

                | 术语 | 别名 | 英文名 |
                | --- | --- | --- |
                | 订单明细 | 订单流水、下单记录 | order_detail |
                """);

        List<String> expansions = TermDictionary.expand(dictionary, List.of("订单流水"));

        assertTrue(expansions.contains("订单明细"));
        assertTrue(expansions.contains("order_detail"));
        assertFalse(expansions.contains("订单流水"), "原词不应重复出现在扩展里");
    }

    @Test
    @DisplayName("扩展上限：单个术语最多补 3 个，避免泛化别名放大扫描")
    void expansionIsCapped() {
        TermDictionary.Dictionary dictionary = parse("业务知识", """
                ## 术语表

                | 术语 | 别名 | 英文名 |
                | --- | --- | --- |
                | 订单 | a1、a2、a3、a4、a5 | order、trade_order、biz_order |
                """);

        List<String> expansions = TermDictionary.expand(dictionary, List.of("订单"));

        assertTrue(expansions.size() <= 3, "超出上限会放大扫描范围，实际=" + expansions);
    }

    @Test
    @DisplayName("重复定义：跨文档撞 key 时记录冲突，生效方在前、被忽略方在后")
    void recordsConflictAcrossDocuments() {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, "业务知识", """
                ## 术语表
                - 订单明细：订单流水
                """, "knowledge:1:旧术语表");
        TermDictionary.merge(dictionary, "业务知识", """
                ## 术语表
                - 订单明细：下单记录
                """, "knowledge:2:新术语表");

        assertEquals(1, dictionary.conflicts().size());
        TermDictionary.TermConflict conflict = dictionary.conflicts().get(0);
        assertEquals("订单明细", conflict.key());
        assertEquals("knowledge:1:旧术语表", conflict.winnerSource());
        assertEquals("knowledge:2:新术语表", conflict.ignoredSource());
    }

    @Test
    @DisplayName("同一篇文档里表格与列表重复书写同一术语，不算冲突")
    void sameDocumentDuplicatesAreNotConflicts() {
        TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
        TermDictionary.merge(dictionary, "业务知识", """
                ## 术语表

                | 术语 | 别名 | 英文名 |
                | --- | --- | --- |
                | 订单明细 | 订单流水 | order_detail |

                - 订单明细：订单流水、order_detail
                """, "knowledge:1:术语表");

        assertTrue(dictionary.conflicts().isEmpty(), "作者自己的重复书写不该报成治理冲突");
    }

    @Test
    @DisplayName("空词典：扩展返回空列表，检索行为不因缺术语表而变化")
    void emptyDictionaryExpandsNothing() {
        assertTrue(TermDictionary.expand(TermDictionary.Dictionary.empty(), List.of("订单", "order")).isEmpty());
    }
}
