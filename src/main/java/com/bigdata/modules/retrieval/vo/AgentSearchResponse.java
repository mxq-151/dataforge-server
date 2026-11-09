package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体库表检索的统一响应信封。
 *
 * <h3>三个字段各自解决什么问题</h3>
 * <ul>
 *   <li>{@code total}：模型据此判断「是收窄还是放宽」，而不是看到一个长度就猜全貌；</li>
 *   <li>{@code truncated}：本次是否只返回了部分结果（含扫描上限触发），
 *       模型据此决定是否继续翻页或换词，避免把「截断」误当「全部」；</li>
 *   <li>{@code notFound}：未命中时的可执行提示，见 {@link NotFoundHint}。</li>
 * </ul>
 *
 * @author dataforge
 */
public class AgentSearchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 已按相关度排序、已截断到 limit 的命中项 */
    private List<AgentSearchItem> items = new ArrayList<>();

    /** 过滤后的命中总数（可能受扫描上限影响，见 truncated 说明） */
    private int total;

    /** 结果是否被截断：命中数超过 limit，或某个关键词的扫描触达上限 */
    private boolean truncated;

    /** 未命中提示；有命中时为 null */
    private NotFoundHint<AgentSearchItem> notFound;

    /** 生效的过滤条件回显，便于模型确认自己传了什么 */
    private AgentFilters appliedFilters;

    /** 由知识库术语表扩展出的检索词；为空表示未命中任何术语或未配置术语表 */
    private List<String> expandedKeywords = new ArrayList<>();

    public List<AgentSearchItem> getItems() { return items; }
    public void setItems(List<AgentSearchItem> items) { this.items = items; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public NotFoundHint<AgentSearchItem> getNotFound() { return notFound; }
    public void setNotFound(NotFoundHint<AgentSearchItem> notFound) { this.notFound = notFound; }
    public AgentFilters getAppliedFilters() { return appliedFilters; }
    public void setAppliedFilters(AgentFilters appliedFilters) { this.appliedFilters = appliedFilters; }
    public List<String> getExpandedKeywords() { return expandedKeywords; }
    public void setExpandedKeywords(List<String> expandedKeywords) { this.expandedKeywords = expandedKeywords; }
}
