package com.bigdata.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;
import com.bigdata.modules.knowledge.enums.DocType;

@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    private String title;
    private DocType docType;
    private String category;
    private String content;
    private String authorName;
    private Long authorId;
    private Integer status;
    private Integer syncToRag;
    private Integer viewCount;
    private String tags;
    private String description;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DocType getDocType() {
        return docType;
    }

    public void setDocType(DocType docType) {
        this.docType = docType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSyncToRag() {
        return syncToRag;
    }

    public void setSyncToRag(Integer syncToRag) {
        this.syncToRag = syncToRag;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
