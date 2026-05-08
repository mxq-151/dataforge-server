package com.bigdata.modules.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentService {

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    public Page<KnowledgeDocument> getDocumentList(int current, int size, String keyword, DocType docType) {
        Page<KnowledgeDocument> page = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(KnowledgeDocument::getTitle, keyword)
                    .or().like(KnowledgeDocument::getContent, keyword));
        }
        if (docType != null) {
            wrapper.eq(KnowledgeDocument::getDocType, docType);
        }
        wrapper.orderByDesc(KnowledgeDocument::getCreateTime);
        return knowledgeDocumentMapper.selectPage(page, wrapper);
    }

    public KnowledgeDocument getDocumentById(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        return doc;
    }

    public void saveDocument(KnowledgeDocument document) {
        document.setStatus(0);
        document.setSyncToRag(0);
        document.setViewCount(0);
        knowledgeDocumentMapper.insert(document);
    }

    public void updateDocument(KnowledgeDocument document) {
        KnowledgeDocument existing = knowledgeDocumentMapper.selectById(document.getId());
        if (existing == null) {
            throw new BusinessException("文档不存在");
        }
        knowledgeDocumentMapper.updateById(document);
    }

    public void deleteDocument(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        knowledgeDocumentMapper.deleteById(id);
    }

    public void incrementViewCount(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        doc.setViewCount(doc.getViewCount() + 1);
        knowledgeDocumentMapper.updateById(doc);
    }

    public void syncToRag(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        doc.setSyncToRag(1);
        knowledgeDocumentMapper.updateById(doc);
    }

    public void publishDocument(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        doc.setStatus(1);
        knowledgeDocumentMapper.updateById(doc);
    }
}
