package com.bigdata.modules.retrieval.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务术语词典 —— 把「业务方怎么说」翻译成「平台里怎么命名」。
 *
 * <h3>为什么从知识库解析，而不是建一张词典表</h3>
 * <p>术语属于平台参考数据，天然跟着业务口径变。再建一张需要人工维护的表，
 * 结果一定是「上线时填了 20 条、半年后没人更新」——这与资产分类曾经腐化的路径一模一样。
 * 知识库已经把术语表当作业务知识的一部分来维护（见《知识管理》），
 * 直接解析现成文档，术语更新的责任人、审核流程、版本记录都已经存在。</p>
 *
 * <h3>支持两种写法（都能被解析）</h3>
 * <pre>
 * ## 术语表
 * | 术语 | 别名 | 英文名 |
 * | --- | --- | --- |
 * | 订单明细 | 订单流水、下单记录 | order_detail |
 *
 * ## 术语表
 * - 订单明细：订单流水、order_detail
 * </pre>
 *
 * <h3>解析失败不报错</h3>
 * <p>没有术语表、表头不认识、格式写错，都只返回空词典 —— 检索退化为「只用原词」，
 * 与接入前行为一致。参考数据的问题不应该让检索整体不可用。</p>
 *
 * @author dataforge
 */
public final class TermDictionary {

    /** 单个词最多扩展到多少个替代表述（防止一个泛化别名把检索放大成全表扫描） */
    private static final int MAX_EXPANSIONS_PER_TERM = 3;

    /** 单次检索最多新增多少个扩展词 */
    private static final int MAX_EXPANSIONS_TOTAL = 12;

    /** 术语段落的标题特征 */
    private static final String SECTION_HINT = "术语";

    private TermDictionary() {
    }

    /**
     * 一条术语。
     *
     * @param term     标准说法
     * @param aliases  同义说法（业务口语、常见错写）
     * @param mappings 英文名 / 命名片段 / 对应实体
     */
    public record Entry(String term, List<String> aliases, List<String> mappings, String source) {
    }

    /**
     * 术语重复定义：同一个 key 被多篇文档写了两套说法。
     *
     * <p>只记录「不同来源」的冲突；同一篇文档内部的重复行（表格与列表各写一次）
     * 不算冲突，否则会把作者自己的书写习惯报成治理问题。</p>
     *
     * @param key           冲突的词（小写归一）
     * @param winnerSource  实际生效的文档
     * @param ignoredSource 被忽略的文档
     */
    public record TermConflict(String key, String winnerSource, String ignoredSource) {
    }

    /** 词典：key 为小写后的标准说法或别名；conflicts 记录跨文档重复定义 */
    public record Dictionary(Map<String, Entry> byKey, List<TermConflict> conflicts) {

        public static Dictionary empty() {
            // 必须是可变集合：merge 会往里写，Map.of()/List.of() 会在第一篇文档解析时抛异常
            return new Dictionary(new LinkedHashMap<>(), new ArrayList<>());
        }

        public boolean isEmpty() {
            return byKey.isEmpty();
        }

        public int size() {
            return byKey.values().stream().map(Entry::term).distinct().toList().size();
        }

        /** 命中某关键词的术语条目（标准说法精确、别名精确、或包含关系） */
        public Entry lookup(String keyword) {
            if (keyword == null || keyword.isBlank()) {
                return null;
            }
            String key = keyword.trim().toLowerCase(Locale.ROOT);
            Entry exact = byKey.get(key);
            if (exact != null) {
                return exact;
            }
            // 包含关系只对 2 字以上的词生效：「订单」能命中「订单明细」，但「单」不会
            if (key.length() < 2) {
                return null;
            }
            Entry best = null;
            int bestLength = 0;
            for (Map.Entry<String, Entry> item : byKey.entrySet()) {
                String candidate = item.getKey();
                if (candidate.length() < 2) {
                    continue;
                }
                if (key.contains(candidate) || candidate.contains(key)) {
                    if (candidate.length() > bestLength) {
                        best = item.getValue();
                        bestLength = candidate.length();
                    }
                }
            }
            return best;
        }
    }

    /**
     * 解析一篇文档，把「术语表」章节里的映射并入词典。
     *
     * @param docTitle 文档标题（标题含「术语」时整篇都按术语表解析）
     * @param content  Markdown 正文
     */
    public static void merge(Dictionary target, String docTitle, String content, String source) {
        if (content == null || content.isBlank()) {
            return;
        }
        boolean wholeDocument = docTitle != null && docTitle.contains(SECTION_HINT);
        for (SectionSplitter.Section section : SectionSplitter.split(content)) {
            if (!wholeDocument && !section.title().contains(SECTION_HINT)) {
                continue;
            }
            parseTable(section.body(), source, target);
            parseBullets(section.body(), source, target);
        }
    }

    /** Markdown 表格写法：按表头定位「术语 / 别名 / 英文」三列，缺列也能工作 */
    private static void parseTable(String body, String source, Dictionary target) {
        int termCol = -1;
        int aliasCol = -1;
        int mappingCol = -1;

        for (String line : body.split("\\R")) {
            if (!line.trim().startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.isEmpty()) {
                continue;
            }
            if (termCol < 0) {
                // 表头行：第一行含「术语/名称/词」的表格才当术语表
                for (int i = 0; i < cells.size(); i++) {
                    String cell = cells.get(i);
                    if (termCol < 0 && (cell.contains("术语") || cell.contains("名称") || cell.equals("词"))) {
                        termCol = i;
                    } else if (aliasCol < 0 && (cell.contains("别名") || cell.contains("同义") || cell.contains("说法"))) {
                        aliasCol = i;
                    } else if (mappingCol < 0 && (cell.contains("英文") || cell.contains("命名")
                            || cell.contains("字段") || cell.contains("表名") || cell.contains("实体"))) {
                        mappingCol = i;
                    }
                }
                if (termCol < 0) {
                    return;
                }
                continue;
            }
            if (isSeparator(cells)) {
                continue;
            }
            String term = cell(cells, termCol);
            if (term.isEmpty()) {
                continue;
            }
            List<String> aliases = splitValues(aliasCol >= 0 ? cell(cells, aliasCol) : "");
            List<String> mappings = splitValues(mappingCol >= 0 ? cell(cells, mappingCol) : "");
            put(target, new Entry(term, aliases, mappings, source));
        }
    }

    /** 列表写法：- 术语：别名1、别名2、order_detail */
    private static void parseBullets(String body, String source, Dictionary target) {
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!(trimmed.startsWith("- ") || trimmed.startsWith("* "))) {
                continue;
            }
            String content = trimmed.substring(2).trim();
            int separator = firstSeparator(content);
            if (separator <= 0) {
                continue;
            }
            String term = content.substring(0, separator).trim();
            String rest = content.substring(separator + 1).trim();
            if (term.isEmpty() || rest.isEmpty() || term.length() > 40) {
                continue;
            }
            List<String> values = splitValues(rest);
            List<String> mappings = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            for (String value : values) {
                if (value.matches("^[A-Za-z][A-Za-z0-9_.]*$")) {
                    mappings.add(value);
                } else {
                    aliases.add(value);
                }
            }
            put(target, new Entry(term, aliases, mappings, source));
        }
    }

    private static int firstSeparator(String content) {
        int colon = content.indexOf('：');
        int ascii = content.indexOf(':');
        if (colon < 0) {
            return ascii;
        }
        if (ascii < 0) {
            return colon;
        }
        return Math.min(colon, ascii);
    }

    private static void put(Dictionary target, Entry entry) {
        register(target, entry.term(), entry);
        for (String alias : entry.aliases()) {
            register(target, alias, entry);
        }
        for (String mapping : entry.mappings()) {
            register(target, mapping, entry);
        }
    }

    private static void register(Dictionary target, String key, Entry entry) {
        if (key == null || key.isBlank()) {
            return;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        // 只有「不同文档重复定义同一术语」才会撞 key —— 知识库文档是 updateById 原地更新，
        // 同一篇文档不存在多版本。撞 key 时保留先到的条目，而调用方已按更新时间倒序传入，
        // 因此实际生效的是最新那篇文档的定义；被忽略的一方记进 conflicts 供治理页面查看。
        Entry existing = target.byKey().putIfAbsent(normalized, entry);
        if (existing != null && !sameSource(existing.source(), entry.source())) {
            target.conflicts().add(new TermConflict(normalized, existing.source(), entry.source()));
        }
    }

    private static boolean sameSource(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split("[、,，;；/\\s]+")) {
            String value = part.trim().replace("`", "");
            if (!value.isEmpty() && !value.equals("-")) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private static List<String> cells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim().replace("`", "").replace("**", ""));
        }
        return cells;
    }

    private static boolean isSeparator(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches("^:?-{2,}:?$")) {
                return false;
            }
        }
        return !cells.isEmpty();
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).trim() : "";
    }

    /**
     * 关键词扩展：返回「标准说法 + 别名 + 英文命名」，不含原词本身。
     *
     * <p>扩展词与原词等权参与召回：英文精确命中表名是强信号，不应该因为
     * 「它来自扩展」而被降权；真正要防的是扩展词数量爆炸，所以有单条 3 个、
     * 单次 12 个的上限。</p>
     */
    public static List<String> expand(Dictionary dictionary, List<String> keywords) {
        if (dictionary == null || dictionary.isEmpty() || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> originals = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword != null) {
                originals.add(keyword.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> expansions = new ArrayList<>();
        for (String keyword : keywords) {
            Entry entry = dictionary.lookup(keyword);
            if (entry == null) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            candidates.add(entry.term());
            candidates.addAll(entry.aliases());
            candidates.addAll(entry.mappings());

            int added = 0;
            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                String value = candidate.trim();
                if (value.length() < 2 || originals.contains(value.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (expansions.contains(value)) {
                    continue;
                }
                expansions.add(value);
                added++;
                if (added >= MAX_EXPANSIONS_PER_TERM || expansions.size() >= MAX_EXPANSIONS_TOTAL) {
                    break;
                }
            }
            if (expansions.size() >= MAX_EXPANSIONS_TOTAL) {
                break;
            }
        }
        return expansions;
    }
}
