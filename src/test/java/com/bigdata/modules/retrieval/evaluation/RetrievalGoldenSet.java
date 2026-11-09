package com.bigdata.modules.retrieval.evaluation;

import com.bigdata.modules.retrieval.vo.AgentFilters;
import com.bigdata.modules.retrieval.vo.AgentSearchItem;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 检索评测基线：加载 golden set、跑用例、算指标。
 *
 * <h3>为什么评测要单独一层</h3>
 * <p>「检索变好了」不能靠体感。没有固定用例集，任何一次参数调整（打分权重、扫描上限、
 * 过滤口径）都无法判断是改善还是回退；更危险的是「修好一个 case、弄坏三个」不会被发现。
 * 这里把用例、执行、指标拆开，使同一份用例既能跑离线 fixture，也能跑联调环境。</p>
 *
 * <h3>指标定义</h3>
 * <ul>
 *   <li><b>top1</b>：第一条命中就在期望集合里 —— 模型最可能只用第一条，它错了一切都错；</li>
 *   <li><b>recall@5</b>：期望集合全部出现在前 5 条 —— 衡量「该找到的是否都给了」；</li>
 *   <li><b>MRR</b>：第一条正确命中的倒数排名 —— 衡量排序质量；</li>
 *   <li><b>约束违例</b>：返回项的环境/分层/业务域与显式过滤条件矛盾 —— 这类错误必须为 0，
 *       因为它意味着「服务端过滤失效」，模型再聪明也救不回来；</li>
 *   <li><b>未命中准确率</b>：该为空的用例是否真的返回空且带 notFound。</li>
 * </ul>
 *
 * @author dataforge
 */
public final class RetrievalGoldenSet {

    private static final int TOP_N = 5;

    private RetrievalGoldenSet() {
    }

    /** 库表用例 */
    public record TableCase(String id, List<String> keywords, AgentFilters filters,
                            List<String> expectTables, String note) {
    }

    /** 知识库用例 */
    public record KnowledgeCase(String id, String query, List<String> docTypes,
                                String expectDocTitle, String expectSection) {
    }

    /** 用例集 */
    public record GoldenSet(int version, List<TableCase> tableCases,
                            List<KnowledgeCase> knowledgeCases, List<TableCase> emptyCases) {
    }

    /** 指标汇总 */
    public record Metrics(int tableCases, int emptyCases, int knowledgeCases,
                          double top1, double recallAt5, double mrr,
                          int violations, double emptyAccuracy,
                          double knowledgeTitleAt3, double knowledgeSectionAt1) {

        /** 可直接贴进评审记录的报告 */
        public String report() {
            return """
                    检索评测报告
                    ─────────────────────────────────────────────
                    库表用例      %d 条    未命中用例 %d 条
                    top1          %.3f
                    recall@5      %.3f
                    MRR           %.3f
                    约束违例      %d 条（要求 0）
                    未命中准确率  %.3f
                    知识库用例    %d 条
                    文档命中@3    %.3f
                    章节命中@1    %.3f
                    """.formatted(tableCases, emptyCases, top1, recallAt5, mrr,
                    violations, emptyAccuracy, knowledgeCases, knowledgeTitleAt3, knowledgeSectionAt1);
        }
    }

    public static GoldenSet load() {
        try (InputStream stream = RetrievalGoldenSet.class.getResourceAsStream("/retrieval/golden-queries.json")) {
            if (stream == null) {
                throw new IllegalStateException("未找到 /retrieval/golden-queries.json");
            }
            return new ObjectMapper()
                    // 用例文件里带 description / note 这类给人看的字段，不参与计算
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(stream, GoldenSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载检索评测基线失败: " + e.getMessage(), e);
        }
    }

    /** 跑库表用例（含空用例），runner 负责把用例变成一次真实检索 */
    public static Metrics evaluate(GoldenSet set, Function<TableCase, AgentSearchResponse> runner) {
        int top1Hits = 0;
        int recallHits = 0;
        double reciprocalRank = 0;
        int violations = 0;

        for (TableCase item : set.tableCases()) {
            AgentSearchResponse response = runner.apply(item);
            List<AgentSearchItem> items = response.getItems() == null ? List.of() : response.getItems();
            Set<String> expected = new LinkedHashSet<>(item.expectTables());

            if (!items.isEmpty() && expected.contains(items.get(0).getName())) {
                top1Hits++;
            }
            Set<String> topNames = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(TOP_N, items.size()); i++) {
                topNames.add(items.get(i).getName());
            }
            if (topNames.containsAll(expected)) {
                recallHits++;
            }
            for (int i = 0; i < items.size(); i++) {
                if (expected.contains(items.get(i).getName())) {
                    reciprocalRank += 1.0 / (i + 1);
                    break;
                }
            }
            violations += countViolations(item.filters(), items);
        }

        int emptyHits = 0;
        for (TableCase item : set.emptyCases()) {
            AgentSearchResponse response = runner.apply(item);
            List<AgentSearchItem> items = response.getItems() == null ? List.of() : response.getItems();
            if (response.getTotal() == 0 && items.isEmpty() && response.getNotFound() != null) {
                emptyHits++;
            }
            violations += countViolations(item.filters(), items);
        }

        double top1 = ratio(top1Hits, set.tableCases().size());
        double recall = ratio(recallHits, set.tableCases().size());
        double mrr = set.tableCases().isEmpty() ? 0 : reciprocalRank / set.tableCases().size();
        double emptyAccuracy = ratio(emptyHits, set.emptyCases().size());

        return new Metrics(set.tableCases().size(), set.emptyCases().size(), set.knowledgeCases().size(),
                round(top1), round(recall), round(mrr), violations, round(emptyAccuracy), 0, 0);
    }

    /** 跑知识库用例，返回「文档命中 / 章节命中」两个指标（合并进同一个 Metrics） */
    public static Metrics evaluateKnowledge(Metrics base, GoldenSet set,
                                            Function<KnowledgeCase, KnowledgeSearchResponse> runner) {
        int docHits = 0;
        int sectionHits = 0;
        for (KnowledgeCase item : set.knowledgeCases()) {
            KnowledgeSearchResponse response = runner.apply(item);
            List<KnowledgeSnippet> items = response.getItems() == null ? List.of() : response.getItems();

            boolean docHit = false;
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                if (equalsTitle(items.get(i), item.expectDocTitle())) {
                    docHit = true;
                    break;
                }
            }
            if (docHit) {
                docHits++;
            }
            if (!items.isEmpty() && equalsTitle(items.get(0), item.expectDocTitle())
                    && item.expectSection() != null
                    && item.expectSection().equals(items.get(0).getSection())) {
                sectionHits++;
            }
        }
        return new Metrics(base.tableCases(), base.emptyCases(), set.knowledgeCases().size(),
                base.top1(), base.recallAt5(), base.mrr(), base.violations(), base.emptyAccuracy(),
                round(ratio(docHits, set.knowledgeCases().size())),
                round(ratio(sectionHits, set.knowledgeCases().size())));
    }

    private static boolean equalsTitle(KnowledgeSnippet snippet, String expected) {
        return expected != null && expected.equals(snippet.getTitle());
    }

    /**
     * 约束违例计数。
     *
     * <p>只看「显式传了约束、返回项却不满足」的情况：没传约束不算违例，
     * 否则会把「全环境检索」误判成错误。</p>
     */
    private static int countViolations(AgentFilters filters, List<AgentSearchItem> items) {
        if (filters == null || items == null || items.isEmpty()) {
            return 0;
        }
        int violations = 0;
        for (AgentSearchItem item : items) {
            if (filters.getEnv() != null && item.getEnv() != null
                    && !filters.getEnv().equalsIgnoreCase(item.getEnv())) {
                violations++;
                continue;
            }
            if (filters.getLayers() != null && !filters.getLayers().isEmpty()
                    && item.getLayerCode() != null
                    && filters.getLayers().stream().noneMatch(layer -> layer.equalsIgnoreCase(item.getLayerCode()))) {
                violations++;
                continue;
            }
            if (filters.getDomains() != null && !filters.getDomains().isEmpty()
                    && item.getDomainCode() != null
                    && filters.getDomains().stream().noneMatch(domain -> domain.equalsIgnoreCase(item.getDomainCode()))) {
                violations++;
            }
        }
        return violations;
    }

    private static double ratio(int hit, int total) {
        return total == 0 ? 0 : (double) hit / total;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 便于测试断言「用例集没有被悄悄删小」 */
    public static int totalCases(GoldenSet set) {
        List<TableCase> all = new ArrayList<>(set.tableCases());
        all.addAll(set.emptyCases());
        return all.size() + set.knowledgeCases().size();
    }
}
