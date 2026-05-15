package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.metadata.vo.TableMetadataVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private TableMetadataService tableMetadataService;

    @GetMapping("/tables")
    public Result<Page<TableMetadataVO>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableMetadata> page = tableMetadataService.getTableList(current, size, datasourceId, databaseName, tableName, keyword);
        Page<TableMetadataVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toTableMetadataVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/table/{id}/columns")
    public Result<List<ColumnMetadataVO>> getTableColumns(@PathVariable Long id) {
        List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(id);
        List<ColumnMetadataVO> voList = columns.stream().map(this::toColumnMetadataVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    private TableMetadataVO toTableMetadataVO(TableMetadata entity) {
        TableMetadataVO vo = new TableMetadataVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ColumnMetadataVO toColumnMetadataVO(ColumnMetadata entity) {
        ColumnMetadataVO vo = new ColumnMetadataVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}