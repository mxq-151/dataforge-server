package com.bigdata.modules.system.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据资源权限批量校验请求。
 * <p>
 * 面向「服务端拿不到结果集」的执行路径（Spark 作业等外部进程）：由作业侧解析出本次
 * 实际访问的数据资源，再回调本接口按<b>提交用户</b>逐个判定是否有权访问。
 * </p>
 * <p>
 * 用户身份二选一：{@link #userId} 优先；未给出时用 {@link #username} 反查
 * （Spark 侧通常只能拿到运行用户名）。两者都缺省视为无法判定，按默认拒绝处理。
 * </p>
 */
public class DataResourcePermissionCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 平台用户 ID，优先使用。 */
    private Long userId;

    /** 平台用户名，userId 缺省时用于反查用户。 */
    private String username;

    /** 待校验的数据资源列表。 */
    private List<Resource> resources = new ArrayList<>();

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources != null ? resources : new ArrayList<>();
    }

    /**
     * 单个待校验的资源。
     */
    public static class Resource implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 资源类型：TABLE（库表） / FILE（文件路径）。 */
        private String resourceType;

        /** 数据源名，可为空（为空则不限定数据源维度）。 */
        private String datasourceName;

        /** 库名，TABLE 类型必填。 */
        private String databaseName;

        /** 表名，TABLE 类型必填。 */
        private String tableName;

        /** 文件路径，FILE 类型必填。 */
        private String path;

        /** 存储配置 ID，FILE 类型可选。 */
        private Long storageConfigId;

        /**
         * 操作类型。
         * TABLE：SELECT / INSERT / UPDATE / DELETE / EXPORT；
         * FILE：READ / WRITE。缺省按只读处理。
         */
        private String operation;

        public String getResourceType() {
            return resourceType;
        }

        public void setResourceType(String resourceType) {
            this.resourceType = resourceType;
        }

        public String getDatasourceName() {
            return datasourceName;
        }

        public void setDatasourceName(String datasourceName) {
            this.datasourceName = datasourceName;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Long getStorageConfigId() {
            return storageConfigId;
        }

        public void setStorageConfigId(Long storageConfigId) {
            this.storageConfigId = storageConfigId;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }
    }
}