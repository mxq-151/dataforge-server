package com.bigdata.modules.integration.service.submit;

/**
 * 提交 / 取消结果。
 */
public class SubmitResult {

    private final String jobId;
    private final String message;
    private final String log;

    public SubmitResult(String jobId, String message, String log) {
        this.jobId = jobId;
        this.message = message;
        this.log = log;
    }

    public String getJobId() {
        return jobId;
    }

    public String getMessage() {
        return message;
    }

    public String getLog() {
        return log;
    }
}
