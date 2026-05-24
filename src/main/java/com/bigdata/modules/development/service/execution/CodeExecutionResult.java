package com.bigdata.modules.development.service.execution;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CodeExecutionResult {

    private boolean success;
    private String executionId;
    private String status;
    private String outputLog;
    private String errorMessage;
    private Integer affectedRows;
    private List<String> columns;
    private List<List<String>> rows;
    private List<SqlResultItem> results;
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

    public static CodeExecutionResult success(String executionId, String outputLog,
                                               List<String> columns, List<List<String>> rows, int affectedRows) {
        CodeExecutionResult result = success(executionId, outputLog);
        result.setColumns(columns);
        result.setRows(rows);
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

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    public void setRows(List<List<String>> rows) {
        this.rows = rows;
    }

    public List<SqlResultItem> getResults() {
        return results;
    }

    public void setResults(List<SqlResultItem> results) {
        this.results = results;
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

    public static class SqlResultItem {

        private int index;
        private String sql;
        private boolean success;
        private String message;
        private List<String> columns;
        private List<List<String>> rows;
        private int affectedRows;

        public static SqlResultItem querySuccess(int index, String sql, List<String> columns, List<List<String>> rows, int rowCount) {
            SqlResultItem item = new SqlResultItem();
            item.setIndex(index);
            item.setSql(sql);
            item.setSuccess(true);
            item.setMessage("查询完成，返回" + rowCount + "行数据");
            item.setColumns(columns);
            item.setRows(rows);
            item.setAffectedRows(rowCount);
            return item;
        }

        public static SqlResultItem dmlSuccess(int index, String sql, int affectedRows) {
            SqlResultItem item = new SqlResultItem();
            item.setIndex(index);
            item.setSql(sql);
            item.setSuccess(true);
            item.setMessage("执行完成，影响" + affectedRows + "行");
            item.setAffectedRows(affectedRows);
            return item;
        }

        public static SqlResultItem failure(int index, String sql, String errorMessage) {
            SqlResultItem item = new SqlResultItem();
            item.setIndex(index);
            item.setSql(sql);
            item.setSuccess(false);
            item.setMessage(errorMessage);
            return item;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
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

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public void setRows(List<List<String>> rows) {
            this.rows = rows;
        }

        public int getAffectedRows() {
            return affectedRows;
        }

        public void setAffectedRows(int affectedRows) {
            this.affectedRows = affectedRows;
        }
    }
}
