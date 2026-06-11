package com.bigdata.modules.devops.dto;

import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.enums.StorageEngineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class DatasourceConfigDTO {

    private Long id;
    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 100, message = "数据源名称不能超过100字符")
    private String datasourceName;

    @NotNull(message = "数据源类型不能为空")
    private DatasourceType datasourceType;

    @NotNull(message = "连接类型不能为空")
    private ConnectionType connectionType;

    @NotNull(message = "存储引擎不能为空")
    private StorageEngineType storageEngine;

    @NotBlank(message = "连接URL不能为空")
    @Size(max = 500, message = "URL长度不能超过500字符")
    private String url;

    private List<String> databaseName;
    private String username;
    private String password;
    private String extraParams;
    private String description;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public void setDatasourceName(String datasourceName) {
        this.datasourceName = datasourceName;
    }

    public DatasourceType getDatasourceType() {
        return datasourceType;
    }

    public void setDatasourceType(DatasourceType datasourceType) {
        this.datasourceType = datasourceType;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(ConnectionType connectionType) {
        this.connectionType = connectionType;
    }

    public StorageEngineType getStorageEngine() {
        return storageEngine;
    }

    public void setStorageEngine(StorageEngineType storageEngine) {
        this.storageEngine = storageEngine;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(List<String> databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
