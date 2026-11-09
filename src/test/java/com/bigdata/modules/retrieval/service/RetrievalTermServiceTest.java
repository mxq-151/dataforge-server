package com.bigdata.modules.retrieval.service;

import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 术语词典加载的缓存与降级口径。
 *
 * <p>两个不能回退的点：TTL 内不重复打库（否则每次检索都全量拉正文）；
 * 加载失败必须 fail-open（不能让可选增强变成检索的单点故障）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrievalTermServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @InjectMocks
    private RetrievalTermService service;

    private static KnowledgeDocument doc(long id, String title, String content) {
        return doc(id, title, content, LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    private static KnowledgeDocument doc(long id, String title, String content, LocalDateTime updateTime) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setTitle(title);
        document.setContent(content);
        document.setDocType(DocType.BUSINESS_KNOWLEDGE);
        document.setUpdateTime(updateTime);
        return document;
    }

    @Test
    @DisplayName("TTL 内只加载一次；失效后重新加载")
    void cachesWithinTtl() {
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(
                doc(1L, "业务知识", """
                        ## 术语表

                        | 术语 | 别名 | 英文名 |
                        | --- | --- | --- |
                        | 订单明细 | 订单流水 | order_detail |
                        """)));

        assertEquals(1, service.dictionary().size());
        assertEquals(1, service.dictionary().size());
        verify(knowledgeDocumentMapper, times(1)).selectList(any());

        service.invalidate();
        assertEquals(1, service.dictionary().size());
        verify(knowledgeDocumentMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("同一术语被多篇文档重复定义：取更新时间最新的一篇，与数据库返回顺序无关")
    void latestDocumentWins() {
        // 故意把旧文档放在前面：证明取舍规则来自 update_time，而不是 mapper 的返回顺序
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(
                doc(1L, "旧术语表", """
                        ## 术语表
                        - 订单明细：旧别名、order_detail
                        """, LocalDateTime.of(2026, 9, 1, 0, 0)),
                doc(2L, "新术语表", """
                        ## 术语表
                        - 订单明细：新别名、order_detail_v2
                        """, LocalDateTime.of(2026, 11, 1, 0, 0))));

        List<String> expansions = service.expand(List.of("订单明细"));

        assertTrue(expansions.contains("新别名"), "应取最新文档的定义，实际=" + expansions);
        assertFalse(expansions.contains("旧别名"), "旧文档的同名术语不应混入，实际=" + expansions);

        // 冲突必须可观测：否则写旧文档的人永远不知道自己被覆盖了
        RetrievalTermService.Snapshot snapshot = service.snapshot();
        assertEquals(1, snapshot.termCount());
        assertEquals(2, snapshot.documentCount());
        assertEquals(1, snapshot.conflicts().size());
        assertEquals("knowledge:2:新术语表", snapshot.conflicts().get(0).winnerSource());
    }

    @Test
    @DisplayName("知识库查询异常时 fail-open：返回空词典而不是抛出")
    void failsOpen() {
        when(knowledgeDocumentMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertTrue(service.dictionary().isEmpty());
        assertTrue(service.expand(List.of("订单明细")).isEmpty());
    }

    @Test
    @DisplayName("多篇文档合并成同一本词典")
    void mergesMultipleDocuments() {
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(
                doc(1L, "业务知识", """
                        ## 术语表
                        - 订单明细：订单流水、order_detail
                        """),
                doc(2L, "业务知识", """
                        ## 术语表
                        - 退款：退货、refund
                        """)));

        assertEquals(2, service.dictionary().size());
        assertTrue(service.expand(List.of("订单流水")).contains("order_detail"));
        assertTrue(service.expand(List.of("退货")).contains("refund"));
    }
}
