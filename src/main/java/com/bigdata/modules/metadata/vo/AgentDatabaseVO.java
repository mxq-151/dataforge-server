package com.bigdata.modules.metadata.vo;

import java.io.Serializable;

public class AgentDatabaseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String databaseName;
    private Integer tableCount;

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Integer getTableCount() {
        return tableCount;
    }

    public void setTableCount(Integer tableCount) {
        this.tableCount = tableCount;
    }
}
