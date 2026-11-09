package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;

/**
 * 知识片段精读请求。
 *
 * <p>两种定位方式二选一：优先 {@code sectionIndex}（来自检索结果的精确位置），
 * 其次 {@code section}（按章节标题模糊匹配）。都不传则返回整篇文档。</p>
 */
public class KnowledgeReadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long docId;

    private Integer sectionIndex;

    private String section;

    /** 返回内容最大字符数，默认 4000、上限 12000 */
    private Integer maxChars;

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public Integer getSectionIndex() { return sectionIndex; }
    public void setSectionIndex(Integer sectionIndex) { this.sectionIndex = sectionIndex; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public Integer getMaxChars() { return maxChars; }
    public void setMaxChars(Integer maxChars) { this.maxChars = maxChars; }
}
