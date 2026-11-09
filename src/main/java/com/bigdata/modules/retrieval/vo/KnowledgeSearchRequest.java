package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 智能体知识库检索请求。
 *
 * <h3>检索粒度是「片段」不是「文档」</h3>
 * <p>知识库单篇文档常常同时包含业务口径、字段说明与操作步骤。按文档返回会把整篇正文
 * 灌进上下文：既稀释了真正命中的那一段，也让模型难以判断「这句话出自哪一节」。
 * 因此这里按章节切分、按命中位置截取片段，精读交给 {@code /knowledge/read}。</p>
 *
 * @author dataforge
 */
public class KnowledgeSearchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 检索意图（仅日志） */
    private String intent;

    /** 检索语句：可以是问题，也可以是一组业务术语 */
    private String query;

    /** 文档类型过滤：business_knowledge / cluster_ops / dev_standard；为空表示不限 */
    private List<String> docTypes;

    /** 返回片段数，默认 5、上限 20 */
    private Integer topK;

    /** 单片段最大字符数，默认 320、上限 1200 */
    private Integer maxSnippetChars;

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getDocTypes() { return docTypes; }
    public void setDocTypes(List<String> docTypes) { this.docTypes = docTypes; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Integer getMaxSnippetChars() { return maxSnippetChars; }
    public void setMaxSnippetChars(Integer maxSnippetChars) { this.maxSnippetChars = maxSnippetChars; }
}
