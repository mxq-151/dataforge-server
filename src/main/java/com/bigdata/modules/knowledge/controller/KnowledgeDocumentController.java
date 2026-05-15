package com.bigdata.modules.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.knowledge.dto.KnowledgeDocumentDTO;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.service.KnowledgeDocumentService;
import com.bigdata.modules.knowledge.vo.KnowledgeDocumentVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/knowledge/document")
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @GetMapping("/list")
    public Result<Page<KnowledgeDocumentVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) DocType docType) {
        Page<KnowledgeDocument> page = knowledgeDocumentService.getDocumentList(current, size, keyword, docType);
        Page<KnowledgeDocumentVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    public Result<KnowledgeDocumentVO> getById(@PathVariable Long id) {
        KnowledgeDocument doc = knowledgeDocumentService.getDocumentById(id);
        return Result.success(toVO(doc));
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody KnowledgeDocumentDTO dto) {
        KnowledgeDocument document = new KnowledgeDocument();
        BeanUtils.copyProperties(dto, document);
        knowledgeDocumentService.saveDocument(document);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody KnowledgeDocumentDTO dto) {
        KnowledgeDocument document = new KnowledgeDocument();
        BeanUtils.copyProperties(dto, document);
        knowledgeDocumentService.updateDocument(document);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeDocumentService.deleteDocument(id);
        return Result.success();
    }

    @PostMapping("/{id}/view")
    public Result<Void> incrementViewCount(@PathVariable Long id) {
        knowledgeDocumentService.incrementViewCount(id);
        return Result.success();
    }

    @PostMapping("/{id}/sync-rag")
    public Result<Void> syncToRag(@PathVariable Long id) {
        knowledgeDocumentService.syncToRag(id);
        return Result.success();
    }

    @PostMapping("/{id}/publish")
    public Result<Void> publishDocument(@PathVariable Long id) {
        knowledgeDocumentService.publishDocument(id);
        return Result.success();
    }

    @GetMapping("/types")
    public Result<List<DocType>> getDocTypes() {
        return Result.success(DocType.allTypes());
    }

    private KnowledgeDocumentVO toVO(KnowledgeDocument entity) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
