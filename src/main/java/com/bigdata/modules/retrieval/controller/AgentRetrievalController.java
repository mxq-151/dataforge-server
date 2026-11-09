package com.bigdata.modules.retrieval.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.retrieval.service.AgentRetrievalService;
import com.bigdata.modules.retrieval.service.KnowledgeSnippetService;
import com.bigdata.modules.retrieval.service.RetrievalTermService;
import com.bigdata.modules.retrieval.vo.AgentSearchRequest;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import com.bigdata.modules.retrieval.vo.AgentTermStatusVO;
import com.bigdata.modules.retrieval.vo.KnowledgeReadRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeReadResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体外部信息检索接口 —— 「召回 + 过滤 + 打分」的统一入口。
 *
 * <h3>为什么单独开一组接口，而不是继续扩 /metadata/agent</h3>
 * <p>这三个端点要同时覆盖元数据与知识库两类来源，共用一个响应信封
 * （items / total / truncated / notFound）。挂在 {@code /metadata} 下会让
 * 「知识库检索」看起来像元数据的一部分，后续接入术语表、指标口径时无处安放。</p>
 *
 * <h3>与既有接口的关系</h3>
 * <ul>
 *   <li>{@code /agent/retrieval/tables} 取代智能体侧对 {@code /metadata/agent/search}
 *       的使用（后者保留给旧版客户端，不做破坏性变更）；</li>
 *   <li>详情（字段清单、DBS 信息）仍走 {@code /metadata/agent/table-detail}，
 *       本组接口只负责「找到」，不负责「读全」——两段式检索是上下文可控的前提。</li>
 * </ul>
 *
 * <p>字段命名以线上实际序列化行为为准：{@code WebMvcConfig} 的 {@code @Primary ObjectMapper}
 * 覆盖了 yml 里的 {@code SNAKE_CASE}，因此请求/响应都是 <b>camelCase</b>
 * （见 {@code ContractMockMvcSupport} 的说明）。智能体侧按 {@code docTypes}、
 * {@code sectionIndex} 传参，写成下划线会静默丢参。</p>
 *
 * @author dataforge
 */
@RestController
@RequestMapping("/agent/retrieval")
public class AgentRetrievalController {

    @Autowired
    private AgentRetrievalService agentRetrievalService;

    @Autowired
    private KnowledgeSnippetService knowledgeSnippetService;

    @Autowired
    private RetrievalTermService retrievalTermService;

    /**
     * 库表检索：table / column / indicator 三类。
     *
     * <p>返回的是「候选 + 证据」，不是最终答案：模型需要根据 {@code matchedBy}、
     * {@code evidence} 与 {@code total} 判断是否收敛，必要时换词或收紧约束再查一次。</p>
     */
    @PostMapping("/tables")
    public Result<AgentSearchResponse> searchTables(@RequestBody(required = false) AgentSearchRequest request) {
        return Result.success(agentRetrievalService.search(request));
    }

    /** 知识库片段检索：按章节切分，只返回命中窗口 */
    @PostMapping("/knowledge")
    public Result<KnowledgeSearchResponse> searchKnowledge(
            @RequestBody(required = false) KnowledgeSearchRequest request) {
        return Result.success(knowledgeSnippetService.search(request));
    }

    /** 知识片段精读：按文档 id + 章节序号取回该节全文 */
    @PostMapping("/knowledge/read")
    public Result<KnowledgeReadResponse> readKnowledge(@RequestBody KnowledgeReadRequest request) {
        return Result.success(knowledgeSnippetService.read(request));
    }

    /**
     * 术语词典诊断：术语条数、参与解析的文档数、重复定义清单。
     *
     * <p>两个高频问题靠这个接口回答，而不是靠猜：</p>
     * <ol>
     *   <li>「模型为什么没认出这个业务词」→ 看 termCount / documentCount 是否为 0；</li>
     *   <li>「我写的术语为什么没生效」→ 看 conflicts 里自己是不是被忽略的那一方。</li>
     * </ol>
     *
     * <p>读接口，不触发权限确认；返回的上限是 100 条冲突，避免一次治理事故把响应撑爆。</p>
     */
    @GetMapping("/terms")
    public Result<AgentTermStatusVO> termStatus() {
        RetrievalTermService.Snapshot snapshot = retrievalTermService.snapshot();
        AgentTermStatusVO vo = new AgentTermStatusVO();
        vo.setTermCount(snapshot.termCount());
        vo.setDocumentCount(snapshot.documentCount());
        vo.setLoadedAt(snapshot.loadedAtEpochMs() <= 0 ? null
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(Instant.ofEpochMilli(snapshot.loadedAtEpochMs())
                        .atZone(ZoneId.systemDefault())));

        List<AgentTermStatusVO.Conflict> conflicts = new ArrayList<>();
        snapshot.conflicts().stream().limit(100).forEach(conflict -> {
            AgentTermStatusVO.Conflict item = new AgentTermStatusVO.Conflict();
            item.setKey(conflict.key());
            item.setWinnerSource(conflict.winnerSource());
            item.setIgnoredSource(conflict.ignoredSource());
            conflicts.add(item);
        });
        vo.setConflicts(conflicts);
        vo.setConflictCount(snapshot.conflicts().size());
        vo.setHint(hintOf(snapshot.termCount(), snapshot.documentCount(), snapshot.conflicts().size()));
        return Result.success(vo);
    }

    private String hintOf(int termCount, int documentCount, int conflictCount) {
        if (documentCount == 0) {
            return "知识库中没有业务知识文档，术语扩展未生效（检索仍可用原词工作）。"
                    + "如需业务词→表名的映射，请新增一篇含「术语表」章节的业务知识文档。";
        }
        if (termCount == 0) {
            return "已加载 " + documentCount + " 篇业务知识文档，但未解析到术语表。"
                    + "请确认章节标题含「术语」，且使用「| 术语 | 别名 | 英文名 |」表格或「- 术语：别名、英文名」列表写法。";
        }
        if (conflictCount > 0) {
            return "存在 " + conflictCount + " 处术语重复定义，已按文档更新时间取最新。"
                    + "请清理 conflicts 中被忽略的旧定义，或把重复术语合并到同一篇文档。";
        }
        return "术语词典正常。";
    }
}
