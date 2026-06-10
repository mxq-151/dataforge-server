package com.bigdata.modules.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class IndicatorFieldDTO {

    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 200, message = "数据源名称不能超过200字符")
    private String datasourceName;

    @NotBlank(message = "库名不能为空")
    @Size(max = 200, message = "库名不能超过200字符")
    private String dbName;

    @NotBlank(message = "表名不能为空")
    @Size(max = 200, message = "表名不能超过200字符")
    private String tblName;

    @NotBlank(message = "字段名不能为空")
    @Size(max = 200, message = "字段名不能超过200字符")
    private String fieldName;

    @NotBlank(message = "字段类型不能为空")
    @Size(max = 200, message = "字段类型不能超过200字符")
    private String fieldType;

    public String getDatasourceName() {
        return datasourceName;
    }

    public void setDatasourceName(String datasourceName) {
        this.datasourceName = datasourceName;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getTblName() {
        return tblName;
    }

    public void setTblName(String tblName) {
        this.tblName = tblName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }
}
