package com.bigdata.modules.retrieval.evaluation;

import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.GoldenSet;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.KnowledgeCase;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.Metrics;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.TableCase;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索回归基线（联调环境，默认跳过）。
 *
 * <h3>什么时候跑</h3>
 * <p>部署到联调/预发后，设置两个环境变量再执行：</p>
 * <pre>
 * DATAFORGE_EVAL_BASE_URL=http://dataforge.example.com:8083
 * DATAFORGE_EVAL_TOKEN=&lt;登录后的 Bearer token&gt;
 * mvn -Dtest=RetrievalLiveEvalTest test
 * </pre>
 *
 * <h3>与离线用例的分工</h3>
 * <p>离线用例验证「逻辑对不对」；这里验证「真实数据下找不找得到」。真实环境里注释缺失、
 * 命名不规范、权限受限都会拉低指标 —— 这些是治理问题，不该用硬阈值卡住构建，
 * 因此这里只断言一件与数据质量无关的事：<b>约束违例必须为 0</b>。其余指标打印出来，
 * 作为发布评审的输入。</p>
 */
@EnabledIfEnvironmentVariable(named = "DATAFORGE_EVAL_BASE_URL", matches = ".+")
class RetrievalLiveEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("联调环境：跑一遍 golden set 并打印指标")
    void liveGoldenSet() {
        String base = baseUrl();
        GoldenSet set = RetrievalGoldenSet.load();

        Metrics tableMetrics = RetrievalGoldenSet.evaluate(set, testCase -> runTable(base, testCase));
        Metrics metrics = RetrievalGoldenSet.evaluateKnowledge(tableMetrics, set,
                testCase -> runKnowledge(base, testCase));

        System.out.println(metrics.report());

        // 约束违例与数据质量无关：它只可能来自服务端过滤逻辑被改坏
        assertEquals(0, metrics.violations(), "约束违例必须为 0");
        assertTrue(metrics.top1() >= 0, "指标计算异常");
    }

    private AgentSearchResponse runTable(String base, TableCase testCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "live-golden:" + testCase.id());
        body.put("keywords", testCase.keywords());
        body.put("filters", testCase.filters());
        body.put("limit", 10);
        JsonNode data = post(base + "/agent/retrieval/tables", body);
        return MAPPER.convertValue(data, AgentSearchResponse.class);
    }

    private KnowledgeSearchResponse runKnowledge(String base, KnowledgeCase testCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "live-golden:" + testCase.id());
        body.put("query", testCase.query());
        body.put("docTypes", testCase.docTypes());
        body.put("topK", 5);
        JsonNode data = post(base + "/agent/retrieval/knowledge", body);
        return MAPPER.convertValue(data, KnowledgeSearchResponse.class);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            String token = System.getenv("DATAFORGE_EVAL_TOKEN");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            JsonNode root = MAPPER.readTree(response.body());
            if (root.path("code").asInt() != 200) {
                throw new IllegalStateException("后端返回异常: " + abbreviate(response.body()));
            }
            return root.path("data");
        } catch (Exception e) {
            throw new IllegalStateException("调用检索接口失败（" + path + "）: " + e.getMessage(), e);
        }
    }

    private static String baseUrl() {
        String raw = System.getenv("DATAFORGE_EVAL_BASE_URL").trim().replaceAll("/+$", "");
        return raw.toLowerCase().endsWith("/api") ? raw : raw + "/api";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
