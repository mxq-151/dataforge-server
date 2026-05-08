package com.bigdata.modules.development.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LSPWebSocketHandler implements WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(LSPWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        logger.info("✅ [LSP] 客户端连接已建立: {}", sessionId);

        Map<String, Object> statusParams = new HashMap<>();
        statusParams.put("status", "connected");
        statusParams.put("message", "LSP 服务已就绪");
        statusParams.put("supportedLanguages", new String[]{"java", "scala", "python"});

        sendToClient(sessionId, createNotification("server/status", statusParams));
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        try {
            String payload = message.getPayload().toString();
            JsonNode jsonNode = objectMapper.readTree(payload);

            if (jsonNode.has("method")) {
                String method = jsonNode.get("method").asText();
                handleLSPRequest(session, method, jsonNode);
            } else {
                logger.debug("[LSP] 收到响应消息");
            }
        } catch (Exception e) {
            logger.error("[LSP] 处理消息失败:", e);
        }
    }

    private void handleLSPRequest(WebSocketSession session, String method, JsonNode request) {
        switch (method) {
            case "initialize" -> handleInitialize(session, request);
            case "textDocument/completion" -> handleCompletion(session, request);
            case "textDocument/hover" -> handleHover(session, request);
            case "textDocument/diagnostic" -> handleDiagnostic(session, request);
            case "shutdown" -> handleShutdown(session);
            default -> logger.info("[LSP] 收到方法: {}", method);
        }
    }

    private void handleInitialize(WebSocketSession session, JsonNode request) {
        int requestId = request.has("id") ? request.get("id").asInt() : 1;

        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("completionProvider", createMap(
                "resolveProvider", true,
                "triggerCharacters", new String[]{".", "(", ","}
        ));
        capabilities.put("hoverProvider", true);
        capabilities.put("signatureHelpProvider", createMap(
                "triggerCharacters", new String[]{"(", ","}
        ));
        capabilities.put("documentHighlightProvider", true);
        capabilities.put("definitionProvider", true);
        capabilities.put("referencesProvider", true);
        capabilities.put("documentSymbolProvider", true);
        capabilities.put("workspaceSymbolProvider", true);

        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "BigData Platform LSP Server");
        serverInfo.put("version", "1.0.0");

        Map<String, Object> result = new HashMap<>();
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        sendResponse(session, requestId, result);
        logger.info("[LSP] 初始化完成");
    }

    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> createMap(Object... keyValuePairs) {
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((K) keyValuePairs[i], (V) keyValuePairs[i + 1]);
        }
        return map;
    }

    private void handleCompletion(WebSocketSession session, JsonNode request) {
        int requestId = request.has("id") ? request.get("id").asInt() : -1;

        try {
            JsonNode params = request.get("params");
            JsonNode position = params.get("position");
            int line = position.get("line").asInt();
            int character = position.get("character").asInt();

            List<Map<String, Object>> completions = generateSmartCompletions(line, character);

            Map<String, Object> result = new HashMap<>();
            result.put("isIncomplete", false);
            result.put("items", completions);

            sendResponse(session, requestId, result);
        } catch (Exception e) {
            logger.error("[LSP] 补全处理失败:", e);
            sendError(session, requestId, -32603, "补全处理失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> generateSmartCompletions(int line, int character) {
        List<Map<String, Object>> items = new ArrayList<>();

        items.add(createCompletionItem(
                "SparkSession", 7,
                "class org.apache.spark.sql.SparkSession",
                "SparkSession 是 Spark 的入口点",
                "SparkSession\nspark = SparkSession.builder()\n  .appName(\"${1:AppName}\")\n  .master(\"local[*]\")\n  .getOrCreate()$0"
        ));

        items.add(createCompletionItem(
                "DataFrame", 7,
                "interface org.apache.spark.sql.DataFrame",
                "带有命名列的分布式数据集合",
                "Dataset<Row> ${1:df} = spark.read().${2|option(\"header\", \"true\")}.csv(\"${3:path}\");$0"
        ));

        items.add(createCompletionItem(
                "read()", 2,
                "DataFrameReader read()",
                "读取外部数据源返回 DataFrame",
                "read().format(\"${1|csv}\").option(\"header\", \"true\").load(\"${2:path}\")$0"
        ));

        items.add(createCompletionItem(
                "select()", 2,
                "DataFrame select(Column... cols)",
                "选择指定的列",
                "select(\"${1:col1}\", \"${2:col2}\")$0"
        ));

        items.add(createCompletionItem(
                "filter()", 2,
                "DataFrame filter(Column condition)",
                "根据条件过滤行",
                "filter(\"${1:condition}\")$0"
        ));

        items.add(createCompletionItem(
                "groupBy()", 2,
                "RelationalGroupedDataset groupBy(Column... cols)",
                "按指定列分组进行聚合",
                "groupBy(\"${1:col}\").agg(${2|count(\"*\").alias(\"count\")})$0"
        ));

        items.add(createCompletionItem(
                "join()", 2,
                "DataFrame join(DataFrame other, String joinExprs, String joinType)",
                "与另一个 DataFrame 进行连接",
                "join(${1:otherDf}, \"${2:joinCondition}\", \"${3|inner}\")$0"
        ));

        items.add(createCompletionItem(
                "write()", 2,
                "DataFrameWriter write()",
                "将 DataFrame 写入外部存储",
                "write().mode(\"${1|overwrite}\").format(\"${2:parquet}\").save(\"${3:path}\")$0"
        ));

        items.add(createCompletionItem(
                "sql()", 2,
                "Dataset<Row> sql(String sqlText)",
                "执行 SQL 查询返回 Dataset",
                "sql(\"${1:SELECT * FROM table}\")$0"
        ));

        items.add(createCompletionItem(
                "createOrReplaceTempView()", 2,
                "void createOrReplaceTempView(String viewName)",
                "创建或替换临时视图",
                "createOrReplaceTempView(\"${1:viewName}\")$0"
        ));

        items.add(createCompletionItem(
                "show()", 2,
                "void show(int numRows)",
                "打印前几行数据到控制台",
                "show(${1|20})$0"
        ));

        items.add(createCompletionItem(
                "collect()", 2,
                "Row[] collect()",
                "将所有数据收集到驱动端数组",
                "collect()$0"
        ));

        items.add(createCompletionItem(
                "map()", 2,
                "<U> RDD<U> map(Function<T, U> f)",
                "对每个元素应用函数并返回新 RDD",
                "map(${1|row -> {\n  // 处理逻辑\n  return ${2:result};\n}})$0"
        ));

        items.add(createCompletionItem(
                "flatMap()", 2,
                "<U> RDD<U> flatMap(Function<T, Iterable<U>> f)",
                "对每个元素应用函数并展平结果",
                "flatMap(${1|item -> {\n  // 返回列表\n  return Arrays.asList(${2:result});\n}})$0"
        ));

        items.add(createCompletionItem(
                "reduce()", 2,
                "T reduce(Function2<T, T, T> f)",
                "使用关联函数聚合元素",
                "reduce((${1|a}, b) -> {\n  return ${2|a + b};\n})$0"
        ));

        return items;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createCompletionItem(String label, int kind, String detail,
                                                      String documentation, String insertText) {
        Map<String, Object> item = new HashMap<>();
        item.put("label", label);
        item.put("kind", kind);
        item.put("detail", detail);
        item.put("documentation", documentation);
        item.put("insertText", insertText);
        item.put("insertTextFormat", 2); // Snippet
        return item;
    }

    private void handleHover(WebSocketSession session, JsonNode request) {
        int requestId = request.has("id") ? request.get("id").asInt() : -1;

        try {
            JsonNode params = request.get("params");
            JsonNode position = params.get("position");

            Map<String, Object> contents = new HashMap<>();
            contents.put("kind", "markdown");
            contents.put("value", "**智能提示**\n\n" +
                    "- 基于 LSP (Language Server Protocol)\n- 提供语义级别的代码补全\n- 支持类型推断和方法签名提示\n\n" +
                    "**当前行**: " + (position.get("line").asInt() + 1));

            sendResponse(session, requestId, createMap("contents", contents));
        } catch (Exception e) {
            logger.error("[LSP] Hover 处理失败:", e);
            sendError(session, requestId, -32603, "Hover 处理失败: " + e.getMessage());
        }
    }

    private void handleDiagnostic(WebSocketSession session, JsonNode request) {
        int requestId = request.has("id") ? request.get("id").asInt() : -1;

        Map<String, Object> result = new HashMap<>();
        result.put("items", new ArrayList<>());

        sendResponse(session, requestId, result);
    }

    private void handleShutdown(WebSocketSession session) {
        logger.info("[LSP] 收到关闭请求");
        sendNotification(session, "exit", null);
    }

    private void sendResponse(WebSocketSession session, int id, Object result) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);

            String message = objectMapper.writeValueAsString(response);
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            logger.error("[LSP] 发送响应失败:", e);
        }
    }

    private void sendError(WebSocketSession session, int id, int code, String message) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("jsonrpc", "2.0");
            error.put("id", id);

            Map<String, Object> errorDetail = new HashMap<>();
            errorDetail.put("code", code);
            errorDetail.put("message", message);
            error.put("error", errorDetail);

            String errorMessage = objectMapper.writeValueAsString(error);
            session.sendMessage(new TextMessage(errorMessage));
        } catch (IOException e) {
            logger.error("[LSP] 发送错误失败:", e);
        }
    }

    private Map<String, Object> createNotification(String method, Object params) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        return notification;
    }

    private void sendNotification(WebSocketSession session, String method, Object params) {
        try {
            String message = objectMapper.writeValueAsString(createNotification(method, params));
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            logger.error("[LSP] 发送通知失败:", e);
        }
    }

    private void sendToClient(String sessionId, Object data) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                String message = objectMapper.writeValueAsString(data);
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                logger.error("[LSP] 发送消息到客户端失败:", e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("[LSP] 传输错误:", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        logger.info("[LSP] 客户端连接已关闭: {}, 状态: {}", sessionId, status);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
