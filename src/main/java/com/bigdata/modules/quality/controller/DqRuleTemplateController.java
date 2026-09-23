package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.QualityPreviewSqlDTO;
import com.bigdata.modules.quality.dto.QualityTemplateSaveDTO;
import com.bigdata.modules.quality.service.DqRuleTemplateService;
import com.bigdata.modules.quality.vo.QualityRuleTemplateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则模板 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/template/*} 系列：
 * 内置模板只读、自定义模板可增删改、预览接口按目标数据源方言渲染 SQL。</p>
 */
@RestController
@RequestMapping("/quality/template")
public class DqRuleTemplateController {

    @Autowired
    private DqRuleTemplateService dqRuleTemplateService;

    /** 内置模板：即 7 类规则类型，由平台预置 */
    @GetMapping("/builtin")
    public Result<List<QualityRuleTemplateVO>> listBuiltin() {
        return Result.success(dqRuleTemplateService.listBuiltin());
    }

    /** 自定义模板分页 */
    @GetMapping
    public Result<Page<QualityRuleTemplateVO>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String keyword) {
        return Result.success(dqRuleTemplateService.pageCustom(current, size, ruleType, keyword));
    }

    @PostMapping
    public Result<Void> create(@RequestBody QualityTemplateSaveDTO dto) {
        dqRuleTemplateService.create(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody QualityTemplateSaveDTO dto) {
        dqRuleTemplateService.update(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dqRuleTemplateService.delete(id);
        return Result.success();
    }

    /**
     * 模板 SQL 预览。
     *
     * <p>返回 {@code {sql}} 而不是裸字符串：前端契约声明的是
     * {@code ApiResponse<{ sql: string }>}，保持结构一致。</p>
     */
    @PostMapping("/preview-sql")
    public Result<Map<String, String>> previewSql(@RequestBody QualityPreviewSqlDTO dto) {
        String sql = dqRuleTemplateService.previewSql(
                dto.getTemplateId(), dto.getDatasourceId(), dto.getDatabaseName(),
                dto.getTableName(), dto.getColumnName(), dto.getParams());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sql", sql);
        return Result.success(body);
    }
}
