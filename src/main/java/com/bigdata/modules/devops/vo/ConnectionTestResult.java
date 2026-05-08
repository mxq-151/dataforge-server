package com.bigdata.modules.devops.vo;

import java.io.Serializable;

public class ConnectionTestResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private String costTime;

    public ConnectionTestResult() {
    }

    public ConnectionTestResult(boolean success, String message, String costTime) {
        this.success = success;
        this.message = message;
        this.costTime = costTime;
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

    public String getCostTime() {
        return costTime;
    }

    public void setCostTime(String costTime) {
        this.costTime = costTime;
    }
}
