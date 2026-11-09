package com.bigdata.modules.retrieval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.bigdata.modules.retrieval.support.QueryTokens;
import com.bigdata.modules.retrieval.support.SectionSplitter;
import com.bigdata.modules.retrieval.vo.KnowledgeReadRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeReadResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSnippet;
import com.bigdata.modules.retrieval.vo.NotFoundHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识库片段级检索与精读。
 *
 * <h3>为什么不是「把整篇文档塞给模型」</h3>
 * <p>旧链路按关键词返回整篇正文（分页 10 篇），一次调用就能把上下文吃掉一半，
 * 而真正相关的那句话可能只有一行。这里改成「章节切分 → 按词元打分 → 截取命中窗口」，
 * 精读交给 {@link #read} 按章节取回。</p>
 *
 * <h3>为什么用词元计数而不是向量</h3>
 * <p>当前知识库没有任何向量索引（见《知识管理》文档：检索方式为关键字匹配）。
 * 在索引层补齐之前，词元计数 + 二元组是唯一能在服务端确定性复现的相关度信号。
 * 这一层的接口形状按「可替换的排序器」设计：将来接入向量或 BM25 时，
 * 只换 {@link #score} 的实现，请求/响应契约不变。</p>
 *
 * @author dataforge
 */
@Service
public class KnowledgeSnippetService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSnippetService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单次检索最多加载多少篇文档（防止 LIKE 全表扫描） */
    private static final int DOC_SCAN_CAP = 300;

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private static final int DEFAULT_SNIPPET_CHARS = 320;
    private static final int MAX_SNIPPET_CHARS = 1200;

    private static final int DEFAULT_READ_CHARS = 4000;
    private static final int MAX_READ_CHARS = 12000;

    /** 同一篇文档最多返回几个片段（保证结果多样性，避免一篇文档刷屏） */
    private static final int MAX_SECTIONS_PER_DOC = 2;

    /** BM25 词频饱和系数（业界默认值，超出后词频继续增加收益递减） */
    private static final double BM25_K1 = 1.2;

    /** BM25 长度归一化强度（0=不归一化，1=完全归一化） */
    private static final double BM25_B = 0.75;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        KnowledgeSearchRequest req = request == null ? new KnowledgeSearchRequest() : request;
        String query = req.getQuery() == null ? "" : req.getQuery().trim();
        int topK = clamp(req.getTopK(), DEFAULT_TOP_K, 1, MAX_TOP_K);
        int snippetChars = clamp(req.getMaxSnippetChars(), DEFAULT_SNIPPET_CHARS, 80, MAX_SNIPPET_CHARS);
        List<DocType> docTypes = parseDocTypes(req.getDocTypes());

        log.info("知识库片段检索: intent={}, query={}, docTypes={}, topK={}",
                req.getIntent(), query, docTypes, topK);

        KnowledgeSearchResponse response = new KnowledgeSearchResponse();
        if (query.isEmpty()) {
            response.setNotFound(hint(List.of(),
                    "缺少 query：请给出要查的业务术语、规范名称或问题后重试。"));
            return response;
        }

        List<String> tokens = QueryTokens.of(query);
        List<KnowledgeDocument> docs = loadCandidates(tokens, docTypes);

        // 先切节再统一打分：BM25 的 idf 依赖「本节在全量候选里有多罕见」，
        // 逐篇算分会让每篇的语料范围不同，分数之间不可比。
        List<Candidate> candidates = new ArrayList<>();
        for (KnowledgeDocument doc : docs) {
            for (SectionSplitter.Section section : SectionSplitter.split(doc.getContent())) {
                candidates.add(new Candidate(doc, section));
            }
        }
        if (candidates.isEmpty()) {
            response.setNotFound(buildNotFound(tokens));
            return response;
        }

        Map<String, Integer> documentFrequency = documentFrequency(candidates, tokens);
        double averageLength = candidates.stream()
                .mapToDouble(item -> Math.max(1, item.section().body().length()))
                .average()
                .orElse(1);

        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            double score = bm25(tokens, candidate, documentFrequency, candidates.size(), averageLength);
            if (score <= 0) {
                continue;
            }
            scored.add(new Scored(candidate.doc(), candidate.section(), score,
                    matchedTerms(tokens, candidate.doc().getTitle(), candidate.section())));
        }

        // 归一到 0~1：对外契约声明 score 只用于同批排序，但不能让调用方看到「1.87」这种
        // 反直觉的量纲。除法保留排序，不改变任何相对关系。
        double max = scored.stream().mapToDouble(Scored::score).max().orElse(1);
        if (max > 0) {
            List<Scored> normalized = new ArrayList<>(scored.size());
            for (Scored item : scored) {
                normalized.add(new Scored(item.doc(), item.section(), round(item.score() / max), item.terms()));
            }
            scored = normalized;
        }

        Map<Long, Integer> perDocCount = new HashMap<>();
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(item -> item.doc().getTitle() == null ? "" : item.doc().getTitle(),
                        String.CASE_INSENSITIVE_ORDER));

        int total = scored.size();
        List<KnowledgeSnippet> items = new ArrayList<>();
        for (Scored item : scored) {
            if (items.size() >= topK) {
                break;
            }
            int used = perDocCount.getOrDefault(item.doc().getId(), 0);
            if (used >= MAX_SECTIONS_PER_DOC) {
                continue;
            }
            perDocCount.put(item.doc().getId(), used + 1);
            items.add(toSnippet(item, snippetChars));
        }

        response.setItems(items);
        response.setTotal(total);
        response.setTruncated(total > items.size());

        if (items.isEmpty()) {
            response.setNotFound(buildNotFound(tokens));
        }
        return response;
    }

    /**
     * 精读：按「章节序号 → 章节标题 → 整篇」的顺序定位，只返回该节。
     *
     * <p>不返回整篇是刻意的：模型已经通过片段判断了相关性，再给整篇只会稀释上下文。
     * 需要其他章节时，响应里的 {@code sections} 就是下一次调用的参数清单。</p>
     */
    public KnowledgeReadResponse read(KnowledgeReadRequest request) {
        if (request == null || request.getDocId() == null) {
            throw new BusinessException("docId 不能为空");
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(request.getDocId());
        if (doc == null) {
            throw new BusinessException(404, "文档不存在: " + request.getDocId());
        }

        List<SectionSplitter.Section> sections = SectionSplitter.split(doc.getContent());
        int maxChars = clamp(request.getMaxChars(), DEFAULT_READ_CHARS, 200, MAX_READ_CHARS);

        KnowledgeReadResponse response = new KnowledgeReadResponse();
        response.setDocId(doc.getId());
        response.setTitle(doc.getTitle());
        response.setDocType(doc.getDocType() == null ? null : doc.getDocType().getCode());
        response.setCategory(doc.getCategory());
        response.setStatus(doc.getStatus());
        response.setUpdateTime(formatTime(doc.getUpdateTime()));
        response.setSections(sections.stream().map(SectionSplitter.Section::title).toList());

        SectionSplitter.Section target = locate(sections, request);
        if (target == null) {
            response.setSection(SectionSplitter.DEFAULT_TITLE);
            response.setSectionIndex(0);
            String content = doc.getContent() == null ? "" : doc.getContent();
            response.setContent(cut(content, maxChars));
            response.setTruncated(content.length() > maxChars);
            return response;
        }

        String content = target.body();
        response.setSection(target.title());
        response.setSectionIndex(target.index());
        response.setContent(cut(content, maxChars));
        response.setTruncated(content != null && content.length() > maxChars);
        return response;
    }

    // ==================================================================
    // 召回与打分
    // ==================================================================

    private List<KnowledgeDocument> loadCandidates(List<String> tokens, List<DocType> docTypes) {
        // 关键词召回用「原始词元」而不是整句：整句 LIKE 对中文问句几乎必然落空
        Set<String> probe = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() >= 2) {
                probe.add(token);
            }
            if (probe.size() >= 6) {
                break;
            }
        }
        if (probe.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(outer -> {
            boolean first = true;
            for (String token : probe) {
                if (first) {
                    outer.like(KnowledgeDocument::getTitle, token)
                            .or().like(KnowledgeDocument::getContent, token)
                            .or().like(KnowledgeDocument::getTags, token);
                    first = false;
                } else {
                    outer.or(w -> w.like(KnowledgeDocument::getTitle, token)
                            .or().like(KnowledgeDocument::getContent, token)
                            .or().like(KnowledgeDocument::getTags, token));
                }
            }
        });
        if (docTypes != null && !docTypes.isEmpty()) {
            wrapper.in(KnowledgeDocument::getDocType, docTypes);
        }
        wrapper.orderByDesc(KnowledgeDocument::getUpdateTime)
                .last("LIMIT " + DOC_SCAN_CAP);
        return knowledgeDocumentMapper.selectList(wrapper);
    }

    /**
     * 片段相关度：BM25 + 字段加权。
     *
     * <p>为什么不是简单词频：词频无法区分「订单」这种到处都有的词与「退款冲正」这种只在
     * 一节里出现的词。BM25 的 idf 项会自动压低常见词、抬高高信息量词，这正是
     * 「业务问句里哪几个词才决定命中」所需要的。</p>
     *
     * <p>字段权重：标题（整篇概括）&gt; 章节标题（本节主题）&gt; 正文。标题命中用二元加成
     * 而不是长度归一化 —— 标题很短，归一化会把它的信号抹平。</p>
     *
     * <p>整句命中不单独加分：{@link QueryTokens} 已经把完整问句作为第一个词元，
     * 它天然是稀有词（idf 高），BM25 自己会奖励它。</p>
     */
    private double bm25(List<String> tokens, Candidate candidate, Map<String, Integer> documentFrequency,
                        int documentCount, double averageLength) {
        String title = lower(candidate.doc().getTitle());
        String heading = lower(candidate.section().title());
        String body = lower(candidate.section().body());
        if (body.isEmpty() && heading.isEmpty()) {
            return 0;
        }
        double length = Math.max(1, body.length());
        double score = 0;
        for (String token : tokens) {
            Integer frequency = documentFrequency.get(token);
            if (frequency == null || frequency == 0) {
                continue;
            }
            double idf = Math.log(1 + (documentCount - frequency + 0.5) / (frequency + 0.5));
            int termFrequency = count(body, token);
            if (termFrequency > 0) {
                double normalized = termFrequency * (BM25_K1 + 1)
                        / (termFrequency + BM25_K1 * (1 - BM25_B + BM25_B * length / averageLength));
                score += idf * normalized;
            }
            if (heading.contains(token)) {
                score += idf * 1.2;
            }
            if (title.contains(token)) {
                score += idf * 1.6;
            }
        }
        return score <= 0 ? 0 : score;
    }

    private Map<String, Integer> documentFrequency(List<Candidate> candidates, List<String> tokens) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String token : tokens) {
            int count = 0;
            for (Candidate candidate : candidates) {
                if (lower(candidate.doc().getTitle()).contains(token)
                        || lower(candidate.section().title()).contains(token)
                        || lower(candidate.section().body()).contains(token)) {
                    count++;
                }
            }
            if (count > 0) {
                frequency.put(token, count);
            }
        }
        return frequency;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private List<String> matchedTerms(List<String> tokens, String docTitle, SectionSplitter.Section section) {
        String haystack = lower(docTitle) + "\n" + lower(section.title()) + "\n" + lower(section.body());
        List<String> terms = new ArrayList<>();
        for (String token : tokens) {
            if (haystack.contains(token)) {
                terms.add(token);
            }
            if (terms.size() >= 6) {
                break;
            }
        }
        return terms;
    }

    private KnowledgeSnippet toSnippet(Scored item, int maxChars) {
        KnowledgeSnippet snippet = new KnowledgeSnippet();
        snippet.setDocId(item.doc().getId());
        snippet.setTitle(item.doc().getTitle());
        snippet.setDocType(item.doc().getDocType() == null ? null : item.doc().getDocType().getCode());
        snippet.setCategory(item.doc().getCategory());
        snippet.setSection(item.section().title());
        snippet.setSectionIndex(item.section().index());
        snippet.setSnippet(window(item.section().body(), item.terms(), maxChars));
        snippet.setScore(item.score());
        snippet.setMatchedTerms(item.terms());
        snippet.setStatus(item.doc().getStatus());
        snippet.setUpdateTime(formatTime(item.doc().getUpdateTime()));
        return snippet;
    }

    /** 命中位置附近截取窗口；片段为空时退回章节开头 */
    private String window(String body, List<String> terms, int maxChars) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String lowerBody = body.toLowerCase(Locale.ROOT);
        int hit = -1;
        for (String term : terms) {
            int index = lowerBody.indexOf(term.toLowerCase(Locale.ROOT));
            if (index >= 0 && (hit < 0 || index < hit)) {
                hit = index;
            }
        }
        if (hit < 0 || body.length() <= maxChars) {
            return cut(body, maxChars);
        }
        int start = Math.max(0, hit - maxChars / 4);
        int end = Math.min(body.length(), start + maxChars);
        start = Math.max(0, end - maxChars);
        String text = body.substring(start, end).trim();
        if (start > 0) {
            text = "…" + text;
        }
        if (end < body.length()) {
            text = text + "…";
        }
        return text;
    }

    private NotFoundHint<KnowledgeSnippet> buildNotFound(List<String> tokens) {
        NotFoundHint<KnowledgeSnippet> hint = new NotFoundHint<>();
        hint.setTried(tokens);
        List<KnowledgeSnippet> nearest = new ArrayList<>();
        for (String token : tokens) {
            if (nearest.size() >= 3) {
                break;
            }
            LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
            wrapper.like(KnowledgeDocument::getTitle, token)
                    .orderByDesc(KnowledgeDocument::getUpdateTime)
                    .last("LIMIT 3");
            for (KnowledgeDocument doc : knowledgeDocumentMapper.selectList(wrapper)) {
                if (nearest.size() >= 3) {
                    break;
                }
                KnowledgeSnippet snippet = new KnowledgeSnippet();
                snippet.setDocId(doc.getId());
                snippet.setTitle(doc.getTitle());
                snippet.setDocType(doc.getDocType() == null ? null : doc.getDocType().getCode());
                snippet.setSection(SectionSplitter.DEFAULT_TITLE);
                snippet.setSectionIndex(0);
                snippet.setSnippet(doc.getDescription() == null ? "" : doc.getDescription());
                snippet.setScore(0.0);
                snippet.setMatchedTerms(List.of(token));
                snippet.setStatus(doc.getStatus());
                snippet.setUpdateTime(formatTime(doc.getUpdateTime()));
                nearest.add(snippet);
            }
        }
        hint.setNearest(nearest);
        hint.setSuggest(nearest.isEmpty()
                ? "知识库未命中。请改用业务术语的另一种说法、字段英文名或规范名称重试；"
                + "确认没有对应文档时，必须在产出物中标注「知识库无此内容」，禁止按常识补写。"
                : "未命中正文内容；nearest 是标题相近的文档，可先读取标题确认是否属于同一主题。");
        return hint;
    }

    private NotFoundHint<KnowledgeSnippet> hint(List<String> tried, String suggest) {
        NotFoundHint<KnowledgeSnippet> hint = new NotFoundHint<>();
        hint.setTried(tried);
        hint.setNearest(List.of());
        hint.setSuggest(suggest);
        return hint;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private SectionSplitter.Section locate(List<SectionSplitter.Section> sections, KnowledgeReadRequest request) {
        if (sections.isEmpty()) {
            return null;
        }
        if (request.getSectionIndex() != null) {
            int index = request.getSectionIndex();
            if (index >= 0 && index < sections.size()) {
                return sections.get(index);
            }
        }
        if (request.getSection() != null && !request.getSection().isBlank()) {
            String expected = request.getSection().trim();
            for (SectionSplitter.Section section : sections) {
                if (section.title().equalsIgnoreCase(expected)) {
                    return section;
                }
            }
            for (SectionSplitter.Section section : sections) {
                if (section.title().contains(expected) || expected.contains(section.title())) {
                    return section;
                }
            }
        }
        return null;
    }

    private List<DocType> parseDocTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<DocType> result = new LinkedHashSet<>();
        for (String value : raw) {
            DocType type = DocType.fromCode(value);
            if (type != null) {
                result.add(type);
            }
        }
        return new ArrayList<>(result);
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int count(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }

    /** 打分前的候选节：先收齐再统一算 idf，避免逐篇算分导致语料范围不一致 */
    private record Candidate(KnowledgeDocument doc, SectionSplitter.Section section) {
    }

    private record Scored(KnowledgeDocument doc, SectionSplitter.Section section,
                          double score, List<String> terms) {
    }
}
