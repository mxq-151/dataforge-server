package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 知识库片段检索响应信封，语义与 {@link AgentSearchResponse} 一致。 */
public class KnowledgeSearchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<KnowledgeSnippet> items = new ArrayList<>();

    private int total;

    private boolean truncated;

    private NotFoundHint<KnowledgeSnippet> notFound;

    public List<KnowledgeSnippet> getItems() { return items; }
    public void setItems(List<KnowledgeSnippet> items) { this.items = items; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public NotFoundHint<KnowledgeSnippet> getNotFound() { return notFound; }
    public void setNotFound(NotFoundHint<KnowledgeSnippet> notFound) { this.notFound = notFound; }
}
