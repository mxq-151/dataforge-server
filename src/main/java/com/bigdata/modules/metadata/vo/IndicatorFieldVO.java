package com.bigdata.modules.metadata.vo;

import java.io.Serializable;

public class IndicatorFieldVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long indicatorsId;
    private String datasourceName;
    private String dbName;
    private String tblName;
    private String fieldName;
    private String fieldType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIndicatorsId() {
        return indicatorsId;
    }

    public void setIndicatorsId(Long indicatorsId) {
        this.indicatorsId = indicatorsId;
    }

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
