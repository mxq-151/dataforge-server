package com.bigdata.modules.development.websocket;

import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.ExecutionLogListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CodeExecutionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CodeExecutionService codeExecutionService;
    private final ExecutionLogListener executionLogListener;

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    public CodeExecutionWebSocketHandler(CodeExecutionService codeExecutionService,
                                         ExecutionLogListener executionLogListener) {
        this.codeExecutionService = codeExecutionService;
        this.executionLogListener = executionLogListener;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);

        CustomUserDetails userDetails = getUserDetails(session);
        if (userDetails != null) {
            sessionUserMap.put(sessionId, String.valueOf(userDetails.getUserId()));
            log.info("WebSocket连接建立: sessionId={}, userId={}", sessionId, userDetails.getUserId());
        } else {
            log.info("WebSocket连接建立: sessionId={} (未认证)", sessionId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        WsMessage wsMessage;

        try {
            wsMessage = objectMapper.readValue(payload, WsMessage.class);
        } catch (Exception e) {
            sendToSession(session, WsMessage.error(null, "消息格式错误: " + e.getMessage()));
            return;
        }

        String type = wsMessage.getType();
        if (type == null) {
            sendToSession(session, WsMessage.error(null, "消息类型不能为空"));
            return;
        }

        switch (type.toUpperCase()) {
            case "PING":
                sendToSession(session, WsMessage.pong());
                break;
            case "EXECUTE":
                handleExecute(session, wsMessage);
                break;
            case "CANCEL":
                handleCancel(session, wsMessage);
                break;
            default:
                sendToSession(session, WsMessage.error(null, "不支持的消息类型: " + type));
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        sessionUserMap.remove(sessionId);
        log.info("WebSocket连接关闭: sessionId={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("WebSocket传输错误: sessionId={}", sessionId, exception);
        sessions.remove(sessionId);
        sessionUserMap.remove(sessionId);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void handleExecute(WebSocketSession session, WsMessage wsMessage) {
        CustomUserDetails userDetails = getUserDetails(session);
        if (userDetails == null) {
            sendToSession(session, WsMessage.error(null, "未登录或登录已过期"));
            return;
        }

        Long userId = userDetails.getUserId();
        String username = userDetails.getUsername();

        CodeExecutionRequest request;
        try {
            String json = objectMapper.writeValueAsString(wsMessage.getData());
            request = objectMapper.readValue(json, CodeExecutionRequest.class);
        } catch (Exception e) {
            sendToSession(session, WsMessage.error(null, "请求参数解析失败: " + e.getMessage()));
            return;
        }

        String executionId = "WS_" + System.currentTimeMillis() + "_" +
                Integer.toHexString(userId.intValue());

        sendToSession(session, WsMessage.status(executionId, "SUBMITTED"));

        executionLogListener.registerLogConsumer(executionId, logLine -> {
            WebSocketSession ws = sessions.get(session.getId());
            if (ws != null && ws.isOpen()) {
                sendToSession(ws, WsMessage.log(executionId, logLine));
            }
        });

        new Thread(() -> {
            try {
                sendToSession(session, WsMessage.status(executionId, "RUNNING"));
                CodeExecutionResult result = codeExecutionService.executeCode(request, userId, username);
                sendToSession(session, WsMessage.result(executionId, result));
            } catch (Exception e) {
                log.error("WebSocket触发的执行异常: executionId={}", executionId, e);
                sendToSession(session, WsMessage.error(executionId, "执行异常: " + e.getMessage()));
            } finally {
                executionLogListener.unregisterLogConsumer(executionId);
            }
        }, "ws-exec-" + executionId).start();
    }

    private void handleCancel(WebSocketSession session, WsMessage wsMessage) {
        CustomUserDetails userDetails = getUserDetails(session);
        if (userDetails == null) {
            sendToSession(session, WsMessage.error(null, "未登录或登录已过期"));
            return;
        }

        String executionId = wsMessage.getExecutionId();
        if (executionId == null || executionId.isEmpty()) {
            sendToSession(session, WsMessage.error(null, "executionId不能为空"));
            return;
        }

        boolean cancelled = codeExecutionService.cancelExecution(executionId, userDetails.getUserId());
        if (cancelled) {
            sendToSession(session, WsMessage.status(executionId, "CANCELLED"));
        } else {
            sendToSession(session, WsMessage.error(executionId, "取消失败，任务可能不存在或已完成"));
        }
    }

    private CustomUserDetails getUserDetails(WebSocketSession session) {
        Object principal = session.getPrincipal();
        if (principal instanceof CustomUserDetails) {
            return (CustomUserDetails) principal;
        }

        Map<String, Object> attributes = session.getAttributes();
        if (attributes != null) {
            Object attrPrincipal = attributes.get("userDetails");
            if (attrPrincipal instanceof CustomUserDetails) {
                return (CustomUserDetails) attrPrincipal;
            }
        }

        return SecurityUtils.getCurrentUserDetails();
    }

    private void sendToSession(WebSocketSession session, WsMessage message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("WebSocket发送消息失败: sessionId={}", session.getId(), e);
        }
    }
}
