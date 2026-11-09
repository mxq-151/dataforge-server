package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 智能体库表检索请求。
 *
 * <h3>为什么是 keywords 数组而不是单个 keyword</h3>
 * <p>业务方说法与物理表名往往不是同一个词（「订单明细」vs {@code dwd_trade_order_detail_d}）。
 * 让模型一次给出多组候选词（业务词、英文词、可能的命名片段），服务端做并集召回并按最佳
 * 命中打分，比让模型串行试错少若干轮往返 —— 而串行试错正是上下文膨胀的主要来源。</p>
 *
 * <p>{@code intent} 不参与检索，只写入日志，用于事后复盘「模型当时想找什么」。</p>
 *
 * @author dataforge
 */
public class AgentSearchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 检索意图（一句话），仅用于日志与审计 */
    private String intent;

    /** 候选关键词，最多 8 个；服务端做去重与空值剔除 */
    private List<String> keywords;

    /** 结构化过滤条件 */
    private AgentFilters filters;

    /** 资产类型：table / column / indicator，缺省 table */
    private List<String> types;

    /** 返回条数，默认 10、上限 50 */
    private Integer limit;

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }
    public AgentFilters getFilters() { return filters; }
    public void setFilters(AgentFilters filters) { this.filters = filters; }
    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
