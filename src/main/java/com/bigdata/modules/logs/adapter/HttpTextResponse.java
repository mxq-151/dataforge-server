package com.bigdata.modules.logs.adapter;

/**
 * 一次 HTTP 取日志的响应。
 */
public class HttpTextResponse {

    private final int status;
    private final String body;
    private final String contentType;

    public HttpTextResponse(int status, String body, String contentType) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.contentType = contentType == null ? "" : contentType;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isText() {
        String lower = contentType.toLowerCase();
        return lower.isEmpty() || lower.startsWith("text/") || lower.contains("json") || lower.contains("xml");
    }
}
