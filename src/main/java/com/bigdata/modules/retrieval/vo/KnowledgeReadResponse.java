package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/** 知识片段精读响应：只返回被请求的那一节，不返回整篇。 */
public class KnowledgeReadResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long docId;

    private String title;

    private String docType;

    private String category;

    /** 实际返回的章节标题 */
    private String section;

    /** 实际返回的章节序号 */
    private Integer sectionIndex;

    /** 章节正文（Markdown） */
    private String content;

    /** 是否因长度上限被截断 */
    private boolean truncated;

    /** 文档全部章节标题，便于模型判断「还需要读哪一节」 */
    private List<String> sections;

    private Integer status;

    private String updateTime;

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public Integer getSectionIndex() { return sectionIndex; }
    public void setSectionIndex(Integer sectionIndex) { this.sectionIndex = sectionIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public List<String> getSections() { return sections; }
    public void setSections(List<String> sections) { this.sections = sections; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
