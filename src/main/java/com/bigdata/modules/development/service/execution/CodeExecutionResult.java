package com.bigdata.modules.development.service.execution;

import java.util.Date;

public class CodeExecutionResult {

    private boolean success;
    private String executionId;
    private String status;
    private String outputLog;
    private String errorMessage;
    private Integer affectedRows;
    private String resultData;
    private Date startTime;
    private Date endTime;
    private Integer durationMs;

    public static CodeExecutionResult success(String executionId, String outputLog) {
        CodeExecutionResult result = new CodeExecutionResult();
        result.setSuccess(true);
        result.setExecutionId(executionId);
        result.setStatus("SUCCESS");
        result.setOutputLog(outputLog);
        return result;
    }

    public static CodeExecutionResult success(String executionId, String outputLog, String resultData, int affectedRows) {
        CodeExecutionResult result = success(executionId, outputLog);
        result.setResultData(resultData);
        result.setAffectedRows(affectedRows);
        return result;
    }

    public static CodeExecutionResult failure(String executionId, String errorMessage) {
        CodeExecutionResult result = new CodeExecutionResult();
        result.setSuccess(false);
        result.setExecutionId(executionId);
        result.setStatus("FAILED");
        result.setErrorMessage(errorMessage);
        return result;
    }

    public static CodeExecutionResult submitted(String executionId, String outputLog) {
        CodeExecutionResult result = new CodeExecutionResult();
        result.setSuccess(true);
        result.setExecutionId(executionId);
        result.setStatus("RUNNING");
        result.setOutputLog(outputLog);
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutputLog() {
        return outputLog;
    }

    public void setOutputLog(String outputLog) {
        this.outputLog = outputLog;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getAffectedRows() {
        return affectedRows;
    }

    public void setAffectedRows(Integer affectedRows) {
        this.affectedRows = affectedRows;
    }

    public String getResultData() {
        return resultData;
    }

    public void setResultData(String resultData) {
        this.resultData = resultData;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }
}
