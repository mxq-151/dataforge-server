package com.bigdata.modules.retrieval.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索词切分与章节切分的口径测试。
 *
 * <p>这两层是纯函数，直接决定召回质量：切得太粗命中不到，切得太细噪声爆炸。
 * 用例覆盖「中文连续词」「带下划线的英文表名」「Markdown 无标题文档」三种典型输入。</p>
 */
class RetrievalTextSupportTest {

    @Test
    @DisplayName("中文：保留整串并补二元组，单字不成词元")
    void chineseProducesBigrams() {
        List<String> tokens = QueryTokens.of("订单退款口径");

        assertTrue(tokens.contains("订单退款口径"));
        assertTrue(tokens.contains("订单"));
        assertTrue(tokens.contains("退款"));
        assertTrue(tokens.contains("口径"));
        assertFalse(tokens.contains("款"), "单字信息量太低，不应成为词元");
    }

    @Test
    @DisplayName("英文表名：整串 + 下划线分段都能被召回")
    void snakeCaseSplitsSegments() {
        List<String> tokens = QueryTokens.of("dwd_trade_order_detail_d");

        assertTrue(tokens.contains("dwd_trade_order_detail_d"));
        assertTrue(tokens.contains("trade"));
        assertTrue(tokens.contains("order"));
        assertTrue(tokens.contains("detail"));
    }

    @Test
    @DisplayName("去重与上限：重复词只保留一次，词元数不超过 12")
    void dedupesAndCaps() {
        List<String> tokens = QueryTokens.of("订单 订单 订单 order order order order order order order order order order");

        assertEquals(tokens.size(), tokens.stream().distinct().count());
        assertTrue(tokens.size() <= 12);
    }

    @Test
    @DisplayName("章节切分：按 Markdown 标题切成独立语义单元")
    void splitsByHeading() {
        List<SectionSplitter.Section> sections = SectionSplitter.split("""
                # 订单主题域

                总述。

                ## 口径

                订单金额 = 支付金额。

                ### 边界

                不含测试单。
                """);

        assertEquals(3, sections.size());
        assertEquals("订单主题域", sections.get(0).title());
        assertEquals("口径", sections.get(1).title());
        assertEquals(2, sections.get(1).level());
        assertTrue(sections.get(1).body().contains("支付金额"));
        assertEquals(1, sections.get(1).index());
    }

    @Test
    @DisplayName("章节切分：没有标题的文档整篇作为「正文」一节")
    void plainTextIsSingleSection() {
        List<SectionSplitter.Section> sections = SectionSplitter.split("订单金额按自然日统计。\nT+1 产出。");

        assertEquals(1, sections.size());
        assertEquals(SectionSplitter.DEFAULT_TITLE, sections.get(0).title());
        assertEquals(0, sections.get(0).level());
        assertTrue(sections.get(0).body().contains("T+1"));
    }

    @Test
    @DisplayName("章节切分：空内容返回空列表，不产生空章节")
    void blankContentProducesNothing() {
        assertTrue(SectionSplitter.split("  ").isEmpty());
        assertTrue(SectionSplitter.split(null).isEmpty());
    }
}
