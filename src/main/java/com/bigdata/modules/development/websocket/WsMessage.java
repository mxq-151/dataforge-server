package com.bigdata.modules.development.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    private String type;
    private String executionId;
    private Object data;
    private String message;
    private Long timestamp;

    public WsMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public static WsMessage execute(String executionId, Object request) {
        WsMessage msg = new WsMessage();
        msg.type = "EXECUTE";
        msg.executionId = executionId;
        msg.data = request;
        return msg;
    }

    public static WsMessage cancel(String executionId) {
        WsMessage msg = new WsMessage();
        msg.type = "CANCEL";
        msg.executionId = executionId;
        return msg;
    }

    public static WsMessage log(String executionId, String logLine) {
        WsMessage msg = new WsMessage();
        msg.type = "LOG";
        msg.executionId = executionId;
        msg.message = logLine;
        return msg;
    }

    public static WsMessage status(String executionId, String status) {
        WsMessage msg = new WsMessage();
        msg.type = "STATUS";
        msg.executionId = executionId;
        msg.data = Map.of("status", status);
        return msg;
    }

    public static WsMessage result(String executionId, Object result) {
        WsMessage msg = new WsMessage();
        msg.type = "RESULT";
        msg.executionId = executionId;
        msg.data = result;
        return msg;
    }

    public static WsMessage error(String executionId, String errorMessage) {
        WsMessage msg = new WsMessage();
        msg.type = "ERROR";
        msg.executionId = executionId;
        msg.message = errorMessage;
        return msg;
    }

    public static WsMessage pong() {
        WsMessage msg = new WsMessage();
        msg.type = "PONG";
        return msg;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
