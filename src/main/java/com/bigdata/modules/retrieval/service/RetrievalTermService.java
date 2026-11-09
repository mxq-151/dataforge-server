package com.bigdata.modules.retrieval.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.bigdata.modules.retrieval.support.TermDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 术语词典的加载与缓存。
 *
 * <h3>为什么带 TTL 缓存</h3>
 * <p>词典来自知识库文档，每次检索都全量拉一遍正文会把「查表」变成「查库」：
 * 一次检索本来只有几条 SQL，加载词典反而成了最重的一步。5 分钟 TTL 是折中：
 * 术语变更属于低频人工操作，最多延迟 5 分钟生效可以接受；同时避免文档正文
 * 长期驻留内存。</p>
 *
 * <h3>为什么失败要吞掉</h3>
 * <p>词典是「锦上添花」：没有它检索仍然能用原词工作。如果因为知识库查询异常
 * 让整个元数据检索失败，等于把一个可选增强变成了单点故障。因此这里 fail-open，
 * 只记 warn 并退回空词典。</p>
 *
 * <h3>同一术语被多篇文档定义时取哪一篇</h3>
 * <p>知识库文档是原地更新（{@code updateById}），同一篇文档永远只有最新一份，不存在多版本。
 * 所谓「新旧」只发生在<b>不同文档</b>都定义了同一个术语（如《交易域术语表》与《全局术语表》）。
 * 取舍规则：取更新时间最新的一篇；更新时间相同则取 id 更大的（后建的）。
 * 该规则在内存里显式排序后执行，不依赖数据库返回顺序。</p>
 *
 * @author dataforge
 */
@Service
public class RetrievalTermService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalTermService.class);

    /** 词典刷新间隔：5 分钟 */
    private static final long TTL_MS = 5 * 60_000L;

    /** 最多读取多少篇业务知识文档（按更新时间倒序，新文档优先） */
    private static final int MAX_DOCS = 200;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    private volatile TermDictionary.Dictionary cached = TermDictionary.Dictionary.empty();
    private volatile int loadedDocuments = 0;
    private volatile long loadedAt = 0L;

    /**
     * 词典快照（供诊断接口使用）。
     *
     * @param termCount     生效的术语条数
     * @param documentCount 参与解析的文档数（0 表示知识库里没有带术语表的业务知识文档）
     * @param loadedAtEpochMs 上次加载时间戳；0 表示尚未加载
     * @param conflicts     跨文档重复定义（生效方 + 被忽略方）
     */
    public record Snapshot(int termCount, int documentCount, long loadedAtEpochMs,
                           List<TermDictionary.TermConflict> conflicts) {
    }

    /** 当前生效的词典（必要时重新加载） */
    public TermDictionary.Dictionary dictionary() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < TTL_MS) {
            return cached;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < TTL_MS) {
                return cached;
            }
            cached = load();
            loadedAt = System.currentTimeMillis();
            return cached;
        }
    }

    /** 关键词扩展；无词典时返回空列表，检索行为与未接入时一致 */
    public List<String> expand(List<String> keywords) {
        return TermDictionary.expand(dictionary(), keywords);
    }

    /** 术语文档更新后可主动失效（供后续治理入口调用） */
    public void invalidate() {
        loadedAt = 0L;
    }

    /** 词典现状：条数、参与文档数、重复定义清单。会触发一次（可能带缓存的）加载。 */
    public Snapshot snapshot() {
        TermDictionary.Dictionary dictionary = dictionary();
        return new Snapshot(dictionary.size(), loadedDocuments, loadedAt, List.copyOf(dictionary.conflicts()));
    }

    private TermDictionary.Dictionary load() {
        try {
            QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
            wrapper.select("id", "title", "content", "doc_type", "update_time")
                    .eq("doc_type", DocType.BUSINESS_KNOWLEDGE.getCode())
                    .orderByDesc("update_time")
                    .last("LIMIT " + MAX_DOCS);
            // 复制一份再排序：selectList 返回的集合不保证可变（单测里也可能是不可变列表）
            List<KnowledgeDocument> docs = new ArrayList<>(knowledgeDocumentMapper.selectList(wrapper));
            // SQL 的 order by 决定「取哪 200 篇」（LIMIT 需要它），合并顺序则在内存里再定一次：
            // 术语冲突的取舍规则不能依赖数据库返回顺序（驱动/分页/索引变化都会影响它）。
            // 这里按更新时间倒序、id 倒序排，配合 TermDictionary 的「先到先得」= 最新文档生效。
            docs.sort(Comparator
                    .comparing(KnowledgeDocument::getUpdateTime,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(KnowledgeDocument::getId,
                            Comparator.nullsLast(Comparator.reverseOrder())));

            TermDictionary.Dictionary dictionary = TermDictionary.Dictionary.empty();
            for (KnowledgeDocument doc : docs) {
                if (doc == null) {
                    continue;
                }
                TermDictionary.merge(dictionary, doc.getTitle(), doc.getContent(), source(doc));
            }
            loadedDocuments = docs.size();
            log.info("术语词典已加载: 术语 {} 条, 文档 {} 篇, 重复定义 {} 处",
                    dictionary.size(), docs.size(), dictionary.conflicts().size());
            logConflicts(dictionary.conflicts());
            return dictionary;
        } catch (RuntimeException e) {
            loadedDocuments = 0;
            log.warn("术语词典加载失败，退回只用原词检索: {}", e.getMessage());
            return TermDictionary.Dictionary.empty();
        }
    }

    /** 来源带上文档标题：诊断重复定义时，「knowledge:7」不如「knowledge:7:交易域术语表」可读 */
    private static String source(KnowledgeDocument doc) {
        String title = doc.getTitle() == null ? "" : doc.getTitle().trim();
        if (title.length() > 40) {
            title = title.substring(0, 40) + "…";
        }
        return "knowledge:" + (doc.getId() == null ? "?" : doc.getId())
                + (title.isEmpty() ? "" : ":" + title);
    }

    /**
     * 重复定义逐条打 warn。
     *
     * <p>「静默取最新」会让写旧文档的人永远不知道自己写的口径已失效，这是治理上的隐患。
     * 日志最多打 10 条，其余指向 {@code GET /agent/retrieval/terms}，避免一次治理事故刷满日志。</p>
     */
    private void logConflicts(List<TermDictionary.TermConflict> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        int limit = Math.min(10, conflicts.size());
        for (int i = 0; i < limit; i++) {
            TermDictionary.TermConflict conflict = conflicts.get(i);
            log.warn("术语重复定义: 「{}」生效={} 忽略={}", conflict.key(),
                    conflict.winnerSource(), conflict.ignoredSource());
        }
        if (conflicts.size() > limit) {
            log.warn("另有 {} 处术语重复定义未打印，详见 GET /agent/retrieval/terms", conflicts.size() - limit);
        }
    }
}
