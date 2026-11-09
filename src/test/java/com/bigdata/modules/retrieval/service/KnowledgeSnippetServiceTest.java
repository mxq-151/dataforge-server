package com.bigdata.modules.retrieval.service;

import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.bigdata.modules.retrieval.vo.KnowledgeReadRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeReadResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSnippet;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 知识库片段检索的口径测试。
 *
 * <p>锁三件事：返回的是「节」不是「篇」、片段围绕命中位置截取、
 * 未命中给出可执行提示而不是空数组。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeSnippetServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @InjectMocks
    private KnowledgeSnippetService service;

    private static final String CONTENT = """
            # 订单主题域

            本文说明订单相关口径。

            ## 口径

            订单金额 = 支付金额 - 退款金额，按自然日统计。

            ## 时效

            T+1 产出，每日 06:00 前完成。
            """;

    private static KnowledgeDocument doc(long id, String title, String content, DocType type) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setDocType(type);
        doc.setStatus(1);
        doc.setCategory("交易");
        doc.setDescription("订单口径说明");
        doc.setUpdateTime(LocalDateTime.of(2026, 10, 1, 9, 30));
        return doc;
    }

    @Test
    @DisplayName("检索：返回命中章节与命中窗口，而不是整篇正文")
    void searchReturnsSectionSnippet() {
        when(knowledgeDocumentMapper.selectList(any()))
                .thenReturn(List.of(doc(12L, "订单主题域口径说明", CONTENT, DocType.BUSINESS_KNOWLEDGE)));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("订单金额");
        KnowledgeSearchResponse response = service.search(request);

        assertFalse(response.getItems().isEmpty());
        KnowledgeSnippet top = response.getItems().get(0);
        assertEquals(12L, top.getDocId());
        assertEquals("口径", top.getSection());
        assertTrue(top.getSnippet().contains("支付金额"), "片段应围绕命中位置截取，实际=" + top.getSnippet());
        assertFalse(top.getSnippet().contains("T+1"), "不应把整篇塞进片段");
        assertTrue(top.getScore() > 0);
        assertNotNull(top.getMatchedTerms());
    }

    @Test
    @DisplayName("检索：同一篇文档最多返回 2 个片段，避免单篇刷屏")
    void searchCapsSectionsPerDocument() {
        when(knowledgeDocumentMapper.selectList(any()))
                .thenReturn(List.of(doc(12L, "订单主题域口径说明", CONTENT, DocType.BUSINESS_KNOWLEDGE)));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("订单 金额 时效");
        request.setTopK(10);
        KnowledgeSearchResponse response = service.search(request);

        assertTrue(response.getItems().size() <= 2);
    }

    @Test
    @DisplayName("检索：空 query 直接给可执行提示")
    void blankQueryReturnsHint() {
        KnowledgeSearchResponse response = service.search(new KnowledgeSearchRequest());

        assertEquals(0, response.getTotal());
        assertNotNull(response.getNotFound());
        assertTrue(response.getNotFound().getSuggest().contains("query"));
    }

    @Test
    @DisplayName("检索：正文无命中时给标题相近的文档，而不是编造内容")
    void missReturnsTitleNearest() {
        when(knowledgeDocumentMapper.selectList(any()))
                .thenReturn(List.of(doc(13L, "订单口径说明", CONTENT, DocType.BUSINESS_KNOWLEDGE)));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("量子纠缠");
        KnowledgeSearchResponse response = service.search(request);

        assertEquals(0, response.getItems().size());
        assertNotNull(response.getNotFound());
        assertTrue(response.getNotFound().getSuggest().contains("知识库未命中")
                        || response.getNotFound().getSuggest().contains("nearest"));
    }

    @Test
    @DisplayName("精读：按章节序号只返回该节正文")
    void readReturnsRequestedSectionOnly() {
        when(knowledgeDocumentMapper.selectById(12L))
                .thenReturn(doc(12L, "订单主题域口径说明", CONTENT, DocType.BUSINESS_KNOWLEDGE));

        KnowledgeReadRequest request = new KnowledgeReadRequest();
        request.setDocId(12L);
        request.setSectionIndex(2);
        KnowledgeReadResponse response = service.read(request);

        assertEquals("时效", response.getSection());
        assertTrue(response.getContent().contains("T+1"));
        assertFalse(response.getContent().contains("支付金额"));
        assertFalse(response.isTruncated());
        assertEquals(3, response.getSections().size());
    }

    @Test
    @DisplayName("精读：按章节标题定位；标题不存在时回退整篇并标记来源")
    void readByTitleAndFallback() {
        when(knowledgeDocumentMapper.selectById(12L))
                .thenReturn(doc(12L, "订单主题域口径说明", CONTENT, DocType.BUSINESS_KNOWLEDGE));

        KnowledgeReadRequest byTitle = new KnowledgeReadRequest();
        byTitle.setDocId(12L);
        byTitle.setSection("口径");
        assertEquals("口径", service.read(byTitle).getSection());

        KnowledgeReadRequest unknown = new KnowledgeReadRequest();
        unknown.setDocId(12L);
        unknown.setSection("不存在的章节");
        KnowledgeReadResponse fallback = service.read(unknown);
        assertEquals("正文", fallback.getSection());
        assertTrue(fallback.getContent().contains("订单金额"));
    }
}
