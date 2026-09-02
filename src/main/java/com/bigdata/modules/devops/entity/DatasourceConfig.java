package com.bigdata.modules.devops.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.config.JsonStringListTypeHandler;
import com.bigdata.common.entity.BaseEntity;
import com.bigdata.common.utils.AesUtils;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import java.time.LocalDateTime;
import java.util.List;

@TableName(value = "datasource_config", autoResultMap = true)
public class DatasourceConfig extends BaseEntity {

    private String datasourceName;
    private DatasourceType datasourceType;
    private ConnectionType connectionType;
    private String url;
    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> databaseName;
    private String username;
    private String password;
    private String extraParams;
    private Integer connectionStatus;
    private LocalDateTime lastTestTime;
    private String description;
    private Long createBy;

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

    /**
     * 获取原始（明文）密码。数据库中存储的密码为 {@link AesUtils#encryptStored} 加密后的密文，
     * 需要通过 {@link AesUtils#decryptStored} 还原为明文供连接、提交等场景使用。
     * <p>
     * 各业务方应统一调用本方法，避免各自重复实现解密逻辑。
     *
     * @param aesUtils AES 工具（实体非 Spring Bean，由调用方注入传入）
     * @return 解密后的明文密码；密码为空或 aesUtils 为 null 时原样返回
     */
    public String getPlainPassword(AesUtils aesUtils) {
        if (password == null || password.isEmpty() || aesUtils == null) {
            return password;
        }
        return aesUtils.decryptStored(password);
    }

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }

    public Integer getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(Integer connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public LocalDateTime getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(LocalDateTime lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }
}
