package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 资产数据预览结果。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的 {@code previewTable()} 契约逐字对应
 * （{@code { columns, rows }}），改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetPreviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 列名（已按列级权限裁剪） */
    private List<String> columns;

    /** 数据行；单元格为字符串，数据库 NULL 保留为 null */
    private List<List<String>> rows;

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<String>> getRows() { return rows; }
    public void setRows(List<List<String>> rows) { this.rows = rows; }
}
