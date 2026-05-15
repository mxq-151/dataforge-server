package com.bigdata.modules.devops.vo;

import java.io.Serializable;

public class StorageConnectionTestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private String storageType;

    public StorageConnectionTestVO() {
    }

    public StorageConnectionTestVO(boolean success, String message, String storageType) {
        this.success = success;
        this.message = message;
        this.storageType = storageType;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }
}
