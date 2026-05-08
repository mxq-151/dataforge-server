package com.bigdata.modules.devops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("storage_config")
public class StorageConfig extends BaseEntity {

    private String storageName;
    private String storageType;
    private String endpoint;
    private String bucketName;
    private String accessKey;
    private String secretKey;
    private String rootPath;
    private String localMountPath;
    private String extraParams;
    private Integer status;

    public String getStorageName() {
        return storageName;
    }

    public void setStorageName(String storageName) {
        this.storageName = storageName;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getLocalMountPath() {
        return localMountPath;
    }

    public void setLocalMountPath(String localMountPath) {
        this.localMountPath = localMountPath;
    }

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
