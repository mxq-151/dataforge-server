package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 知识片段命中项。
 *
 * <p>{@code sectionIndex} 是精读时的定位参数：模型先拿片段判断相关性，
 * 需要全文时再按「文档 id + 章节序号」取回该节，避免二次检索产生新的歧义。</p>
 *
 * @author dataforge
 */
public class KnowledgeSnippet implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 id，精读时回传 */
    private Long docId;

    /** 文档标题 */
    private String title;

    /** 文档类型编码 */
    private String docType;

    /** 分类 */
    private String category;

    /** 命中的章节标题；文档无标题时为「正文」 */
    private String section;

    /** 章节序号，从 0 开始 */
    private Integer sectionIndex;

    /** 命中位置附近的片段 */
    private String snippet;

    /** 相关度（仅用于同批排序） */
    private Double score;

    /** 命中的检索词 */
    private List<String> matchedTerms;

    /** 文档状态：1=已发布，0=草稿；草稿需在产出物中标注「未发布」 */
    private Integer status;

    /** 文档更新时间 */
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
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public List<String> getMatchedTerms() { return matchedTerms; }
    public void setMatchedTerms(List<String> matchedTerms) { this.matchedTerms = matchedTerms; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
